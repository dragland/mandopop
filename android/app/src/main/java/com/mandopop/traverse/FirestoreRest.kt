package com.mandopop.traverse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Raw strings from one `cards/{cardId}` document, before hanzi extraction. */
data class CardDoc(
    val cardId: String,
    val title: String?,
    val strings: List<String>,
)

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
     * Card content, used to recover the hanzi behind an opaque `cardId`.
     *
     * Returns every string on the card rather than named fields — see [HanziExtractor] for why the
     * field keys are deliberately not hardcoded. Content is effectively static, so callers cache
     * aggressively; null means the card is genuinely gone (404), not a transient failure.
     */
    suspend fun card(authorUserName: String, cardId: String): CardDoc? = withContext(Dispatchers.IO) {
        val token = auth.idToken()
        val url = "$BASE/userNames/${encode(authorUserName)}/cards/${encode(cardId)}"
        val body = try {
            Http.get(url, token)
        } catch (error: TraverseException) {
            if (error.statusCode == 404) return@withContext null
            throw error
        }

        val document = JSONObject(body)
        val fields = document.optJSONObject("fields") ?: return@withContext CardDoc(cardId, null, emptyList())
        val strings = mutableListOf<String>()
        collectStrings(fields, strings, depth = 0)
        CardDoc(
            cardId = cardId,
            title = FirestoreValues.string(fields, "title") ?: FirestoreValues.string(fields, "id"),
            strings = strings,
        )
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
        private val BASE =
            "https://firestore.googleapis.com/v1/projects/${TraverseAuth.PROJECT_ID}" +
                "/databases/(default)/documents"
        private const val PAGE_SIZE = 300
        private const val MAX_PAGES = 50
        private const val MAX_FIELD_DEPTH = 3
        private const val MAX_FIELD_STRINGS = 64
    }
}
