package com.mandopop.traverse

import android.content.Context
import android.util.Log
import com.mandopop.data.CardContentEntity
import com.mandopop.data.DueCounter
import com.mandopop.data.MandopopDatabase
import com.mandopop.data.ScheduleEntity
import com.mandopop.data.SyncStateEntity
import com.mandopop.dictionary.DictionaryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
data class DueExample(
    val hanzi: String,
    val gloss: String,
    /**
     * A studied course sentence containing [hanzi] with every word known (i+1 cloze,
     * spec.md §4.1), rotated daily. Null falls back to showing the bare word.
     */
    val sentence: String? = null,
)

/**
 * How much of the deck has been read into the vocabulary index.
 *
 * Surfaced rather than merely logged: an incomplete index looks exactly like a user who has studied
 * less, and every immersion feature is built on this table.
 *
 * Three numbers, not two, because the whole deck now drains inside a single sync — there is no slow
 * climb to watch, so a shortfall has to be *named* rather than inferred from a ratio nobody
 * remembers the healthy value of. [fetched] short of [eligible] means still draining;
 * [readable] short of [fetched] means cards were read and found illegible, which is a parser bug.
 */
data class Coverage(
    val eligible: Int,
    val fetched: Int,
    val readable: Int,
    val words: Int,
)

sealed interface SyncOutcome {
    object NotSignedIn : SyncOutcome
    data class Success(
        val dueCount: Int,
        val liveCount: Int,
        val pulled: Boolean,
        val example: DueExample? = null,
        val coverage: Coverage = Coverage(0, 0, 0, 0),
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
    private val dictionary = DictionaryRepository(appContext)
    private val vocabulary = CardVocabulary(firestore, dictionary, database.cardContentDao())
    private val knownWords = KnownWordIndex(
        dictionary,
        database.cardContentDao(),
        database.knownWordDao(),
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
        database.knownWordDao().deleteAll()
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
        return resolveExample(DueCounter.endOfDayMs(LocalDate.now(zone), zone))
    }

    suspend fun state(): SyncStateEntity = database.syncStateDao().get() ?: SyncStateEntity()

    /** Vocabulary coverage from the local mirror, without touching the network. */
    suspend fun localCoverage(): Coverage {
        val content = database.cardContentDao()
        return Coverage(
            eligible = content.eligibleCardCount(),
            fetched = content.fetchedCount(CardParser.VERSION),
            readable = content.readableCount(CardParser.VERSION),
            words = database.knownWordDao().count(),
        )
    }

    /**
     * Runs on [Dispatchers.Default] because the callers cannot be trusted to.
     *
     * The settings screen launches this from `rememberCoroutineScope()`, which is the main thread.
     * Network and Room hop off it themselves, but card parsing and segmentation are plain CPU work
     * over the whole deck — tens of thousands of regex passes and substrings on a first sync — and
     * would otherwise land on the UI thread.
     */
    suspend fun sync(force: Boolean = false): SyncOutcome = withContext(Dispatchers.Default) {
        syncInternal(force)
    }

    private suspend fun syncInternal(force: Boolean): SyncOutcome {
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
                database.cardContentDao().deleteOrphans().let {
                    if (it > 0) Log.i(TAG, "Forgot content for $it cards no longer in the deck")
                }
                // Banked before the content drain. The drain can now fail loudly, and if that
                // discarded the heartbeat too, a card-content problem would buy a fresh ~1,000-row
                // schedule pull on Traverse's project every fifteen minutes until it was fixed.
                previous = previous.copy(
                    lastPullAtMs = now,
                    lastEventDate = today.toString(),
                    lastEventCount = reviewCount,
                )
                syncStateDao.put(previous.copy(lastSyncAtMs = now))
            }

            val boundary = DueCounter.endOfDayMs(today, zone)
            val backfill = vocabulary.backfill(limit = CONTENT_LIMIT)
            // Suspending a lesson changes which words count as known without touching card
            // content, so the pull matters here as much as the fetch does. Run before the failure
            // is raised: whatever did arrive is still worth indexing, and a guard that suppressed
            // the rebuild would leave a stale index with nothing to retrigger it.
            if (backfill.changed || pull || database.knownWordDao().count() == 0) {
                knownWords.rebuild()
            }
            backfill.failure?.let { throw it }

            val dueCount = scheduleDao.countDueBefore(boundary)
            val liveCount = scheduleDao.countLive()
            val example = resolveExample(boundary)

            syncStateDao.put(
                previous.copy(
                    lastSyncAtMs = now,
                    lastSuccessAtMs = now,
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
                coverage = localCoverage(),
            )
        } catch (cancellation: CancellationException) {
            // Not a failure, and reporting it as one is actively misleading now that a sync can run
            // for the best part of a minute: leaving the settings screen mid-drain would otherwise
            // persist "Job was cancelled" as lastError and greet the user with "Needs attention".
            throw cancellation
        } catch (error: Exception) {
            val message = error.message ?: error::class.java.simpleName
            Log.e(TAG, "Traverse sync failed", error)
            // Persisting the error must not itself escape — callers treat sync() as total.
            runCatching { syncStateDao.put(previous.copy(lastSyncAtMs = now, lastError = message)) }
            SyncOutcome.Failure(message, statusCode = (error as? TraverseException)?.statusCode)
        }
    }

    /** The due word, upgraded to an i+1 cloze sentence when a studied one qualifies. */
    private suspend fun resolveExample(boundaryMs: Long): DueExample? {
        val row = database.cardContentDao().dueExample(boundaryMs) ?: return null
        val word = row.hanzi ?: return null
        val meaning = row.english ?: return null
        return DueExample(word, meaning, clozeFor(word))
    }

    private suspend fun clozeFor(word: String): String? {
        val sentences = database.cardContentDao().sentencesContaining(word)
        if (sentences.isEmpty()) return null
        val known = database.frontierDao().knownHanzi().toHashSet()
        if (known.isEmpty()) return null
        val candidates = mutableSetOf<String>()
        for (sentence in sentences) candidates += Segmenter.candidates(sentence)
        val dictWords = dictionary.knownSimplified(candidates)
        val zone = ZoneId.systemDefault()
        return ClozePicker.pick(
            sentences,
            word,
            isWord = { it in dictWords || it in known },
            isKnown = { it in known },
            seed = ClozePicker.seed(LocalDate.now(zone).toEpochDay(), word),
        )
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

        /**
         * Ceiling on card documents fetched in one sync — a runaway guard, not pacing.
         *
         * Comfortably above a full deck (~940 eligible cards today), because the point is for a
         * fresh install to have a usable vocabulary index within its first sync rather than after
         * a day of trickling. Being cut short is harmless: the invariant resumes next run.
         */
        const val CONTENT_LIMIT = 1_500
    }
}
