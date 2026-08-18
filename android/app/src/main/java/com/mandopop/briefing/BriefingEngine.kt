package com.mandopop.briefing

import android.content.Context
import android.util.Log
import com.mandopop.data.FrontierWord
import com.mandopop.data.MandopopDatabase
import com.mandopop.dictionary.DictionaryRepository
import com.mandopop.notification.DueNotifier
import com.mandopop.traverse.Segmenter
import com.mandopop.traverse.TraverseSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * The daily-briefing orchestrator: gather inputs → code picks → compose → verify → show.
 *
 * Computed lazily on shade-pull (spec.md §4.4/§5): opening the shade is the only moment the
 * sentence is looked at, so that is when it is made — fresh exactly at glance time, ~zero cost
 * otherwise. Results are cached in memory keyed by an input signature; clearing a notification,
 * an event passing, the local day rolling over, or the vocabulary growing regenerates — an
 * unchanged moment does not. Nothing is persisted: after process death the briefing is simply
 * absent until the next pull. Every attempt's raw material is kept on [lastAttempt] because the
 * whole point of this build is auditioning the composer — a silently discarded model output
 * would make the audition undebuggable.
 */
object BriefingEngine {
    private const val TAG = "MandopopBriefing"

    /** SystemUI announces the shade, the keyguard, volume — a real pull-down at most this often. */
    private const val SHADE_THROTTLE_MS = 20_000L

    private const val MODEL_ROUNDS = 2

    enum class Source { NANO, TEMPLATE }

    data class Briefing(
        val sentence: String,
        val frontier: FrontierWord?,
        val source: Source,
        val generatedAtMs: Long,
    ) {
        /**
         * Expanded-view block. The sentence shows hanzi only; the frontier word is the one
         * exception and arrives glossed — it is un-learned, so there is no recall to defeat
         * (introduction rule), and pre-noticing a curriculum word is the point.
         */
        fun expandedBlock(): String = buildString {
            append(sentence)
            frontier?.takeIf { sentence.contains(it.hanzi) }?.let {
                append('\n').append(it.hanzi)
                it.pinyin?.let { p -> append(' ').append(p) }
                it.english?.let { e -> append(" — ").append(e) }
            }
        }
    }

    /** Everything the settings screen needs to judge the composer. */
    data class Attempt(
        val atMs: Long,
        val inputsSummary: String,
        val gist: String?,
        val promptWords: List<String>?,
        val modelOutputs: List<String>,
        val rejections: List<String>,
        val outcome: String,
    )

    @Volatile
    private var stored: Briefing? = null

    /**
     * Day-gated: a briefing says "今天", so yesterday's must never ride this morning's worker
     * repost. The stale case regenerates on the next shade pull; until then the notification
     * simply carries no briefing, which is honest.
     */
    val current: Briefing?
        get() = stored?.takeIf { sameLocalDay(it.generatedAtMs) }

    @Volatile
    var lastAttempt: Attempt? = null
        private set

    val composer = GeminiNanoComposer()

    private val mutex = Mutex()
    private var lastSignature: Int? = null

    /** Held for the process lifetime, like the service's own instance — reopening the SQLite
     *  dictionary on every shade-pull was measurable, avoidable I/O. Guarded by [mutex]. */
    private var dictionary: DictionaryRepository? = null

    @Volatile
    private var lastShadeMs = 0L

    /** Generation stamp of an ambient (zero-due) briefing the user swiped away. */
    @Volatile
    private var dismissedGenerationMs = 0L

    /**
     * The briefing the zero-due ambient line may show: none if the user dismissed this exact
     * generation. Reposts come from many doors (worker, resume, shade-pull) and must not
     * resurrect a dismissed line; a *newly generated* briefing shows again.
     */
    fun ambientBriefing(): Briefing? = current?.takeIf { it.generatedAtMs != dismissedGenerationMs }

    fun ambientDismissed() {
        current?.let { dismissedGenerationMs = it.generatedAtMs }
    }

    /** Shade-pull entry point, called from the accessibility service on SystemUI window events. */
    fun shadePulled(context: Context, scope: CoroutineScope) {
        val now = System.currentTimeMillis()
        if (now - lastShadeMs < SHADE_THROTTLE_MS) return
        lastShadeMs = now
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Default) {
            try {
                if (refresh(appContext)) repostFromLocal(appContext)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "shade-pull briefing refresh failed", error)
            }
        }
    }

    /**
     * Regenerates if the inputs moved (or [force]). Returns whether anything changed — callers
     * repost the notification only on true, so an idle shade-pull never flickers it. Hops to
     * [Dispatchers.Default] itself: the settings panel calls this from the main dispatcher, and
     * the calendar provider query and shade snapshot must not run there.
     */
    suspend fun refresh(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            mutex.withLock { refreshLocked(context, force) }
        }

    private suspend fun refreshLocked(context: Context, force: Boolean): Boolean {
        val inputs = BriefingInputs(
            nowMs = System.currentTimeMillis(),
            events = CalendarSource.eventsRemainingToday(context),
            notifications = NotificationCatcher.activeNotifications(),
            screen = ScreenTextMonitor.snapshot,
        )
        val database = MandopopDatabase.get(context)
        // The signature folds in the local date (yesterday's "今天…" must not survive the
        // rollover just because the shade looks the same) and the vocabulary size (the
        // "no known words yet" outcome must retry once a sync has landed, not wait for an
        // input to move).
        val knownCount = database.knownWordDao().count()
        val today = LocalDate.now(ZoneId.systemDefault())
        val signature = (inputs.signature() * 31 + knownCount) * 31 + today.hashCode()
        if (!force && signature == lastSignature) return false

        val shadeState = when {
            !NotificationCatcher.isEnabled(context) -> "access off"
            !NotificationCatcher.isConnected() -> "unbound"
            else -> "${inputs.notifications.size}"
        }
        val summary = "${inputs.events.size} events · $shadeState notifications" +
            " · screen=${inputs.screen?.packageName ?: "none"}"

        val known = database.frontierDao().knownHanzi().toHashSet()
        if (known.isEmpty()) {
            finish(signature, null, Attempt(inputs.nowMs, summary, null, null, emptyList(),
                emptyList(), "no known words yet — sign in and sync first"))
            return true
        }
        // A word can sit on two cards — one live (so it is known) and one still-suspended (so
        // it matches the frontier query). The frontier's whole meaning is "un-learned"; without
        // this filter the introduction gloss could hand over a learned word's answer.
        val frontier = database.frontierDao().frontierWords().filterNot { it.hanzi in known }

        val modelOutputs = mutableListOf<String>()
        val rejections = mutableListOf<String>()
        val dictionary = dictionary ?: DictionaryRepository(context.applicationContext)
            .also { dictionary = it }

        val plan = BriefingPicker.plan(inputs, known, frontier, ZoneId.systemDefault()) { word ->
            dictionary.lookup(word, 1).firstOrNull()?.simplified
        }
        if (plan == null) {
            finish(signature, null, Attempt(inputs.nowMs, summary, null, null, emptyList(),
                emptyList(), "no relevant known vocabulary in today's inputs"))
            return true
        }

        val allowed: (String) -> Boolean = { it in known || it == plan.frontier?.hanzi }
        suspend fun verdictOf(sentence: String): BriefingVerifier.Verdict {
            val dictWords = dictionary.knownSimplified(Segmenter.candidates(sentence))
            return BriefingVerifier.verify(
                sentence,
                isWord = { it in dictWords || allowed(it) },
                isAllowed = allowed,
            )
        }

        var result: Briefing? = null

        if (composer.status() == GeminiNanoComposer.Status.AVAILABLE) {
            var avoid = emptyList<String>()
            for (round in 0 until MODEL_ROUNDS) {
                val raw = try {
                    composer.generate(BriefingPrompt.build(plan.gist, plan.words, avoid))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    rejections += "model error: ${GeminiNanoComposer.describe(error)}"
                    break
                }
                modelOutputs += raw
                val sentence = BriefingPrompt.extractSentence(raw)
                when (val verdict = verdictOf(sentence)) {
                    is BriefingVerifier.Verdict.Pass -> {
                        result = Briefing(sentence, plan.frontier, Source.NANO, inputs.nowMs)
                        break
                    }
                    is BriefingVerifier.Verdict.Fail -> {
                        rejections += "nano: ${verdict.reason}"
                        avoid = (avoid + verdict.unknownWords).distinct().take(6)
                    }
                }
            }
        }

        if (result == null) {
            for (candidate in TemplateComposer.candidates(plan)) {
                when (val verdict = verdictOf(candidate)) {
                    is BriefingVerifier.Verdict.Pass -> {
                        result = Briefing(candidate, plan.frontier, Source.TEMPLATE, inputs.nowMs)
                        break
                    }
                    is BriefingVerifier.Verdict.Fail -> rejections += "template \"$candidate\": ${verdict.reason}"
                }
            }
        }

        finish(
            signature,
            result,
            Attempt(
                atMs = inputs.nowMs,
                inputsSummary = summary,
                gist = plan.gist,
                promptWords = plan.words,
                modelOutputs = modelOutputs,
                rejections = rejections,
                outcome = when (result?.source) {
                    Source.NANO -> "Gemini Nano composed it"
                    Source.TEMPLATE -> "template fallback"
                    null -> "nothing verified — no briefing shown"
                },
            ),
        )
        return true
    }

    private fun sameLocalDay(thenMs: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate() ==
            LocalDate.now(zone)
    }

    private fun finish(signature: Int, briefing: Briefing?, attempt: Attempt) {
        lastSignature = signature
        stored = briefing
        lastAttempt = attempt
        if (briefing == null) {
            Log.w(TAG, "no briefing: ${attempt.outcome}; rejections=${attempt.rejections}")
        } else {
            Log.i(TAG, "briefing [${briefing.source}] ${briefing.sentence}")
        }
    }

    private suspend fun repostFromLocal(context: Context) {
        val sync = TraverseSync(context)
        if (!sync.isSignedIn()) return
        DueNotifier.showLocal(context, sync.localDueCount(), sync.localLiveCount(), sync.localExample())
    }
}
