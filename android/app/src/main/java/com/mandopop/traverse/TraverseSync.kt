package com.mandopop.traverse

import android.content.Context
import android.util.Log
import com.mandopop.data.CardContentEntity
import com.mandopop.data.DueCounter
import com.mandopop.data.MandopopDatabase
import com.mandopop.data.ScheduleEntity
import com.mandopop.data.SyncStateEntity
import com.mandopop.dictionary.DictionaryRepository
import java.time.LocalDate
import java.time.ZoneId

/**
 * A word that is due right now.
 *
 * [gloss] holds *every* reading, not a chosen one. Picking a single sense means guessing: 东西 is
 * `dōng xī` "east and west" and `dōng xi` "thing", and CC-CEDICT row order does not know which the
 * card teaches. Since the gloss is hidden until the user asks for it, showing all readings is both
 * more honest and more useful than a ranking heuristic.
 */
data class DueExample(val hanzi: String, val gloss: String)

sealed interface SyncOutcome {
    object NotSignedIn : SyncOutcome
    data class Success(
        val dueCount: Int,
        val liveCount: Int,
        val pulled: Boolean,
        val example: DueExample? = null,
    ) : SyncOutcome
    data class Failure(val message: String, val statusCode: Int? = null) : SyncOutcome
}

/**
 * Pulls Traverse SRS state into the local mirror and recomputes the due count.
 *
 * Cost control: the expensive full pull (~1,000 document reads, billed to Traverse's project) is
 * gated behind a one-document heartbeat on today's events doc. The due count itself is recomputed
 * locally every run, because it changes with the clock even when nothing synced.
 */
class TraverseSync(context: Context) {
    private val appContext = context.applicationContext
    private val auth = TraverseAuth.get(appContext)
    private val firestore = FirestoreRest(auth)
    private val database = MandopopDatabase.get(appContext)
    private val vocabulary = CardVocabulary(
        firestore,
        DictionaryRepository(appContext),
        database.cardContentDao(),
    )

    suspend fun isSignedIn(): Boolean = auth.isSignedIn()
    suspend fun signedInEmail(): String? = auth.email()

    suspend fun signIn(email: String, password: String) = auth.signIn(email, password)

    /**
     * Clears credentials *and* the local mirror. Always route sign-out through here rather than
     * [TraverseAuth.signOut]: leaving stale schedules behind means a subsequent sign-in as a
     * different account could serve the previous user's counts before the first pull lands.
     */
    suspend fun signOut() {
        auth.signOut()
        database.scheduleDao().deleteAll()
        database.cardContentDao().deleteAll()
        database.syncStateDao().put(SyncStateEntity())
    }

    /** Due count from the local mirror, without touching the network. */
    suspend fun localDueCount(): Int {
        val zone = ZoneId.systemDefault()
        return database.scheduleDao()
            .countDueBefore(DueCounter.endOfDayMs(LocalDate.now(zone), zone))
    }

    /** Live (non-suspended) card count from the local mirror. */
    suspend fun localLiveCount(): Int = database.scheduleDao().countLive()

    /** A resolved due word from the local mirror, without touching the network. */
    suspend fun localExample(): DueExample? {
        val zone = ZoneId.systemDefault()
        return database.cardContentDao()
            .dueExample(DueCounter.endOfDayMs(LocalDate.now(zone), zone))
            ?.toDueExample()
    }

    suspend fun state(): SyncStateEntity = database.syncStateDao().get() ?: SyncStateEntity()

    suspend fun sync(force: Boolean = false): SyncOutcome {
        val uid = auth.uid() ?: return SyncOutcome.NotSignedIn

        val scheduleDao = database.scheduleDao()
        val syncStateDao = database.syncStateDao()
        val now = System.currentTimeMillis()
        var previous = SyncStateEntity()

        return try {
            previous = syncStateDao.get() ?: SyncStateEntity()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val reviewCount = firestore.reviewCountOn(uid, today)

            // The heartbeat only moves when a review is *answered*. Anything else that changes the
            // deck — unsuspending a lesson, rescheduling, or reviews landing in a different day's
            // events doc than our local date — is invisible to it, so fall back to a periodic pull.
            val stale = previous.lastPullAtMs?.let { now - it > MAX_STALENESS_MS } ?: true

            val pull = force ||
                stale ||
                scheduleDao.count() == 0 ||
                previous.lastEventDate != today.toString() ||
                previous.lastEventCount != reviewCount

            if (pull) {
                val rows = firestore.allSchedules(uid)
                // Only refuse an empty response if we previously had data — otherwise a genuinely
                // empty account would fail forever instead of just showing zero.
                if (rows.isEmpty() && previous.lastSuccessAtMs != null) {
                    throw TraverseException(
                        "Traverse returned no schedules — refusing to wipe local data",
                    )
                }
                scheduleDao.replaceAll(rows.map(::toEntity))
            }

            val boundary = DueCounter.endOfDayMs(today, zone)
            vocabulary.backfill(
                author = scheduleDao.anyAuthorUserName() ?: DEFAULT_AUTHOR,
                boundaryMs = boundary,
                batchSize = CONTENT_BATCH,
            )

            val dueCount = scheduleDao.countDueBefore(boundary)
            val liveCount = scheduleDao.countLive()
            val example = database.cardContentDao().dueExample(boundary)?.toDueExample()

            syncStateDao.put(
                previous.copy(
                    lastSyncAtMs = now,
                    lastSuccessAtMs = now,
                    lastPullAtMs = if (pull) now else previous.lastPullAtMs,
                    lastEventDate = today.toString(),
                    lastEventCount = reviewCount,
                    lastError = null,
                ),
            )
            SyncOutcome.Success(
                dueCount = dueCount,
                liveCount = liveCount,
                pulled = pull,
                example = example,
            )
        } catch (error: Exception) {
            val message = error.message ?: error::class.java.simpleName
            Log.e(TAG, "Traverse sync failed", error)
            // Persisting the error must not itself escape — callers treat sync() as total.
            runCatching { syncStateDao.put(previous.copy(lastSyncAtMs = now, lastError = message)) }
            SyncOutcome.Failure(message, statusCode = (error as? TraverseException)?.statusCode)
        }
    }

    private fun CardContentEntity.toDueExample(): DueExample? {
        val word = hanzi ?: return null
        val meaning = english ?: return null
        return DueExample(word, meaning)
    }

    /** Re-resolves a word's readings for the notification's reveal action. */
    suspend fun glossFor(hanzi: String): String? = vocabulary.glossFor(hanzi)

    private fun toEntity(row: ScheduleRow) = ScheduleEntity(
        id = row.id,
        cardId = row.cardId,
        authorUserName = row.authorUserName,
        template = row.template,
        topicId = row.topicId,
        promptNr = row.promptNr,
        queue = row.queue,
        suspended = row.suspended,
        dueTimeMs = row.dueTimeMs,
        intervalDays = row.interval,
        easeFactor = row.easeFactor,
        repetitions = row.repetitions,
        lapses = row.lapses,
    )

    private companion object {
        const val TAG = "TraverseSync"

        /** Upper bound on how long the mirror may drift when the heartbeat sees no activity. */
        const val MAX_STALENESS_MS = 6 * 60 * 60 * 1000L

        /** Card documents fetched per sync, so the backfill spreads over runs instead of stalling. */
        const val CONTENT_BATCH = 25

        /** Fallback only; the author is normally read from the synced rows. */
        const val DEFAULT_AUTHOR = "Mandarin_Blueprint"
    }
}
