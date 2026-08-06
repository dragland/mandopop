package com.mandopop.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local mirror of one Traverse schedule row (one card *prompt*, not one card).
 *
 * Indexed on the two columns the due query filters by, since that query runs on every notification
 * refresh.
 */
@Entity(
    tableName = "schedules",
    indices = [Index(value = ["suspended", "due_time_ms"]), Index(value = ["card_id"])],
)
data class ScheduleEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "card_id") val cardId: String,
    /** Needed to build the card-content path; every row is `Mandarin_Blueprint` today. */
    @ColumnInfo(name = "author_user_name") val authorUserName: String,
    @ColumnInfo(name = "template") val template: String,
    @ColumnInfo(name = "topic_id") val topicId: String,
    @ColumnInfo(name = "prompt_nr") val promptNr: Int,
    @ColumnInfo(name = "queue") val queue: String,
    @ColumnInfo(name = "suspended") val suspended: Boolean,
    @ColumnInfo(name = "due_time_ms") val dueTimeMs: Long,
    @ColumnInfo(name = "interval_days") val intervalDays: Double,
    @ColumnInfo(name = "ease_factor") val easeFactor: Double,
    @ColumnInfo(name = "repetitions") val repetitions: Int,
    @ColumnInfo(name = "lapses") val lapses: Int,
)

/**
 * The word — or sentence — a card teaches, recovered from its content.
 *
 * Cached indefinitely: card content is effectively static. [hanzi] is null when the card carries no
 * CJK at all; [english] is null when the characters exist but no dictionary entry matches (radicals,
 * mnemonic props). Both are cached negatives, so a card is never refetched on a whim.
 *
 * [parserVersion] is what makes that caching safe. A cached negative is indistinguishable from a
 * parse failure at read time, so before this column existed a card the extractor could not read was
 * marked done *forever* — 55 of 211 rows were in exactly that state. Bumping
 * [CardParser.VERSION] demotes every row below it from "done" to "stale", and the backfill
 * invariant refetches them with no migration, no adb, and no bookkeeping.
 */
@Entity(tableName = "card_content")
data class CardContentEntity(
    @PrimaryKey @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "hanzi") val hanzi: String?,
    @ColumnInfo(name = "pinyin") val pinyin: String?,
    @ColumnInfo(name = "english") val english: String?,
    @ColumnInfo(name = "fetched_at_ms") val fetchedAtMs: Long,
    @ColumnInfo(name = "parser_version", defaultValue = "0") val parserVersion: Int = 0,
    /**
     * Whether [hanzi] is a sentence rather than a headword.
     *
     * Recorded rather than inferred from length. [english] used to be non-null only when CC-CEDICT
     * resolved the characters, and the notification relied on that — but a sentence card carries
     * its own translation, so the two came apart. A short sentence like `你好吗？` would otherwise
     * pass a length test, reach the notification, and leave Reveal looking up a whole sentence in
     * a dictionary that has no such entry.
     */
    @ColumnInfo(name = "is_sentence", defaultValue = "0") val isSentence: Boolean = false,
)

/**
 * One Chinese word this user has been taught, derived from [CardContentEntity].
 *
 * Purely derived and freely rebuildable: everything here comes from `card_content` joined to
 * non-suspended `schedules`, with no network. That is deliberate — the derivation rules are the part
 * most likely to be wrong, and getting them wrong must not cost a refetch.
 *
 * [source] records how the word was learned rather than how well. There is no strength gradation:
 * exposure is the bar, and augmentation features want more known words rather than fewer.
 */
@Entity(tableName = "known_words")
data class KnownWordEntity(
    @PrimaryKey @ColumnInfo(name = "hanzi") val hanzi: String,
    @ColumnInfo(name = "pinyin") val pinyin: String?,
    @ColumnInfo(name = "english") val english: String?,
    /** [SOURCE_TAUGHT] when a card teaches this word directly, [SOURCE_SENTENCE] when it was only
     *  met inside a drilled sentence. */
    @ColumnInfo(name = "source") val source: String,
) {
    companion object {
        const val SOURCE_TAUGHT = "taught"
        const val SOURCE_SENTENCE = "sentence"
    }
}

/** Single-row table (id is always 0) holding sync bookkeeping. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "last_sync_at_ms") val lastSyncAtMs: Long? = null,
    @ColumnInfo(name = "last_success_at_ms") val lastSuccessAtMs: Long? = null,
    /** Last time the full schedule table was actually pulled, for the staleness fallback. */
    @ColumnInfo(name = "last_pull_at_ms") val lastPullAtMs: Long? = null,
    /** Date of the events doc behind [lastEventCount], as ISO `YYYY-MM-DD`. */
    @ColumnInfo(name = "last_event_date") val lastEventDate: String? = null,
    /** Review count seen in that events doc — the heartbeat value. */
    @ColumnInfo(name = "last_event_count") val lastEventCount: Int = -1,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
