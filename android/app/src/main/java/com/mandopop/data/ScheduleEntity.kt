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
 * The word a card teaches, recovered from its content and resolved against CC-CEDICT.
 *
 * Cached indefinitely — card content is effectively static, and the fetch is one network read per
 * card. [hanzi] is null when the card has no CJK on it at all (pinyin drills); [english] is null
 * when the characters exist but no dictionary entry matches (radicals, mnemonic props). Both are
 * cached negatives so we do not refetch them forever.
 */
@Entity(tableName = "card_content")
data class CardContentEntity(
    @PrimaryKey @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "hanzi") val hanzi: String?,
    @ColumnInfo(name = "pinyin") val pinyin: String?,
    @ColumnInfo(name = "english") val english: String?,
    @ColumnInfo(name = "fetched_at_ms") val fetchedAtMs: Long,
)

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
