package com.mandopop.traverse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate

/** One `userNames/{uid}/schedules/{id}` document, decoded. One row per card *prompt*. */
data class ScheduleRow(
    val id: String,
    val cardId: String,
    val authorUserName: String,
    val template: String,
    val topicId: String,
    val promptNr: Int,
    val queue: String,
    val suspended: Boolean,
    val dueTimeMs: Long,
    val interval: Double,
    val easeFactor: Double,
    val repetitions: Int,
    val lapses: Int,
)

/**
 * One `cards/{cardId}` document, before extraction.
 *
 * The named field map — `Chinese`, `Pinyin`, `English Translation`, `HANZI` and so on — is looked
 * up case-insensitively, because a few cards carry both `WORD` and `Word`. [template] comes from
 * the document rather than from a schedule row: a card with two prompts has two rows, and picking
 * one of them to decide how to read the card is arbitrary.
 */
data class CardDoc(
    val cardId: String,
    val title: String?,
    val template: String?,
    private val namedFields: Map<String, String>,
) {
    /** First non-blank value among [keys], or null. */
    fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        namedFields[key.lowercase()]?.takeIf { it.isNotBlank() }
    }

    companion object {
        fun of(
            cardId: String,
            title: String?,
            template: String?,
            fields: Map<String, String>,
        ) = CardDoc(cardId, title, template, fields.mapKeys { it.key.lowercase() })
    }
}

class FirestoreRest(private val auth: TraverseAuth) {

    /**
     * Pulls every schedule document for [uid].
     *
     * ~1,000 documents in practice. Callers should gate this behind the events heartbeat rather
     * than running it on every tick — these reads bill to Traverse's Firebase project.
     */
    suspend fun allSchedules(uid: String): List<ScheduleRow> = withContext(Dispatchers.IO) {
        val token = auth.idToken()
        val rows = mutableListOf<ScheduleRow>()
        var pageToken: String? = null
        var pages = 0
        var dropped = 0

        do {
            val url = buildString {
                append("$BASE/userNames/${encode(uid)}/schedules?pageSize=$PAGE_SIZE")
                pageToken?.let { append("&pageToken=${encode(it)}") }
            }
            val json = JSONObject(Http.get(url, token))
            val documents = json.optJSONArray("documents")
            if (documents != null) {
                for (index in 0 until documents.length()) {
                    val document = documents.optJSONObject(index) ?: continue
                    val row = parseSchedule(document)
                    if (row == null) dropped++ else rows.add(row)
                }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            pages++
        } while (pageToken != null && pages < MAX_PAGES)

        if (pageToken != null) {
            throw TraverseException("Schedule pagination exceeded $MAX_PAGES pages — aborting")
        }
        // A few unparseable rows are tolerable; a lot means Traverse changed its schema and the
        // due count is quietly wrong. Fail loudly rather than under-reporting.
        val total = rows.size + dropped
        if (dropped > 0 && dropped * 10 > total) {
            throw TraverseException("Could not parse $dropped of $total schedules — schema changed?")
        }
        rows
    }

    /**
     * Number of reviews logged on [date], or 0 when the day has no document yet.
     *
     * This is the sync heartbeat: one document read tells us whether anything changed, so the
     * expensive [allSchedules] pull only runs when the count actually moved.
     */
    suspend fun reviewCountOn(uid: String, date: LocalDate): Int = withContext(Dispatchers.IO) {
        val token = auth.idToken()
        val url = "$BASE/userNames/${encode(uid)}/events/${encode(date.toString())}"
        val body = try {
            Http.get(url, token)
        } catch (error: TraverseException) {
            // No document for a day with no reviews yet — not a failure.
            if (error.statusCode == 404) return@withContext 0
            throw error
        }
        val fields = JSONObject(body).optJSONObject("fields") ?: return@withContext 0
        FirestoreValues.array(fields, "review").size
    }

    /**
     * Card content for up to [CARD_BATCH_SIZE] cards in one request, used to recover the hanzi
     * behind opaque `cardId`s.
     *
     * `documents:batchGet` takes explicit document names, so it needs no query permissions and
     * works with the same user token as everything else — verified against Traverse's project at
     * 150 documents (1.0 MB) in a single POST.
     *
     * Callers chunk and pace: the whole backfill is ~730 one-off reads against a collection
     * Traverse already serves, so the politeness that matters is never having two requests in
     * flight, and chunk-at-a-time also keeps a megabyte of JSON on the heap instead of ten.
     *
     * Cards absent from the response come back as `null` — a real, cacheable negative. Anything
     * else throws: a half-filled index that reports success is the failure mode this design exists
     * to prevent.
     */
    suspend fun cards(
        authorUserName: String,
        cardIds: List<String>,
    ): Map<String, CardDoc?> = withContext(Dispatchers.IO) {
        if (cardIds.isEmpty()) return@withContext emptyMap()
        require(cardIds.size <= CARD_BATCH_SIZE) {
            "batchGet takes at most $CARD_BATCH_SIZE documents; got ${cardIds.size}"
        }
        val prefix = "projects/${TraverseAuth.PROJECT_ID}/databases/(default)/documents" +
            "/userNames/$authorUserName/cards/"
        val names = JSONArray().apply { cardIds.forEach { put(prefix + it) } }
        val response = Http.postJson(
            "$BASE:batchGet",
            JSONObject().put("documents", names).toString(),
            bearerToken = auth.idToken(),
            readTimeoutMs = Http.LONG_TIMEOUT_MS,
        )
        val results = cardIds.associateWithTo(mutableMapOf<String, CardDoc?>()) { null }
        readBatch(JSONArray(response), results)
        results
    }

    /** Folds a `batchGet` response into [into], leaving missing documents at their null default. */
    private fun readBatch(response: JSONArray, into: MutableMap<String, CardDoc?>) {
        for (index in 0 until response.length()) {
            val found = response.optJSONObject(index)?.optJSONObject("found") ?: continue
            val cardId = FirestoreValues.documentId(found.optString("name")) ?: continue
            val fields = found.optJSONObject("fields")
            if (fields == null) {
                into[cardId] = CardDoc.of(cardId, null, null, emptyMap())
                continue
            }
            // The card's content lives one level down, in its own `fields` map; the outer level is
            // Traverse's own bookkeeping (notes, reviews, graph links).
            val content = fields.optJSONObject("fields")?.optJSONObject("mapValue")
                ?.optJSONObject("fields")
            into[cardId] = CardDoc.of(
                cardId = cardId,
                title = FirestoreValues.string(fields, "title")
                    ?: FirestoreValues.string(fields, "id"),
                template = FirestoreValues.string(fields, "template"),
                fields = content?.let(::readNamedFields).orEmpty(),
            )
        }
    }

    /**
     * The card's named content fields, flattened to one string each.
     *
     * A field is nearly always a plain string; the nested walk is kept for the handful that wrap
     * their value, so a field never reads as empty just because it was stored one level deeper.
     */
    private fun readNamedFields(content: JSONObject): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        for (key in content.keys()) {
            val value = content.optJSONObject(key) ?: continue
            val text = value.optString("stringValue").takeIf { it.isNotBlank() }
                ?: mutableListOf<String>().also { collectStrings(
                    JSONObject().put(key, value), it, depth = 0,
                ) }.joinToString(" ").takeIf { it.isNotBlank() }
            if (text != null) fields[key] = text
        }
        return fields
    }

    /** Walks the Firestore typed-value tree gathering every `stringValue`. */
    private fun collectStrings(fields: JSONObject, into: MutableList<String>, depth: Int) {
        if (depth > MAX_FIELD_DEPTH || into.size >= MAX_FIELD_STRINGS) return
        for (key in fields.keys()) {
            val value = fields.optJSONObject(key) ?: continue
            when {
                value.has("stringValue") ->
                    value.optString("stringValue").takeIf { it.isNotBlank() }?.let(into::add)
                value.has("mapValue") -> value.optJSONObject("mapValue")
                    ?.optJSONObject("fields")
                    ?.let { collectStrings(it, into, depth + 1) }
                value.has("arrayValue") -> {
                    val values = value.optJSONObject("arrayValue")?.optJSONArray("values") ?: continue
                    for (index in 0 until values.length()) {
                        val element = values.optJSONObject(index) ?: continue
                        when {
                            element.has("stringValue") ->
                                element.optString("stringValue").takeIf { it.isNotBlank() }
                                    ?.let(into::add)
                            element.has("mapValue") -> element.optJSONObject("mapValue")
                                ?.optJSONObject("fields")
                                ?.let { collectStrings(it, into, depth + 1) }
                        }
                    }
                }
            }
            if (into.size >= MAX_FIELD_STRINGS) return
        }
    }

    private fun parseSchedule(document: JSONObject): ScheduleRow? {
        val id = FirestoreValues.documentId(document.optString("name")) ?: return null
        val fields = document.optJSONObject("fields") ?: return null
        val dueTimeMs = FirestoreValues.timestampMs(fields, "dueTime") ?: return null

        return ScheduleRow(
            id = id,
            cardId = FirestoreValues.string(fields, "cardId").orEmpty(),
            authorUserName = FirestoreValues.string(fields, "authorUserName").orEmpty(),
            template = FirestoreValues.string(fields, "template").orEmpty(),
            topicId = FirestoreValues.string(fields, "topicId").orEmpty(),
            promptNr = FirestoreValues.long(fields, "promptNr")?.toInt() ?: 0,
            queue = FirestoreValues.string(fields, "queue").orEmpty(),
            // Absent `suspended` means an active card; only an explicit true excludes it.
            suspended = FirestoreValues.boolean(fields, "suspended") ?: false,
            dueTimeMs = dueTimeMs,
            interval = FirestoreValues.double(fields, "interval") ?: 0.0,
            easeFactor = FirestoreValues.double(fields, "easeFactor") ?: 2.5,
            repetitions = FirestoreValues.long(fields, "repetitions")?.toInt() ?: 0,
            lapses = FirestoreValues.long(fields, "lapses")?.toInt() ?: 0,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private val BASE: String
            get() = "https://firestore.googleapis.com/v1/projects/${TraverseAuth.PROJECT_ID}" +
                "/databases/(default)/documents"
        private const val PAGE_SIZE = 300
        private const val MAX_PAGES = 50
        private const val MAX_FIELD_DEPTH = 3
        private const val MAX_FIELD_STRINGS = 64

        /** ~6.9 KB per card document, so 150 sits an order of magnitude under the 10 MiB cap. */
        const val CARD_BATCH_SIZE = 150

        /** Sequential requests are the actual courtesy; this just keeps the burst unhurried. */
        const val CARD_BATCH_PAUSE_MS = 3_000L
    }
}
