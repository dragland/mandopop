package com.mandopop.briefing

import android.content.Context
import android.util.Log
import com.mandopop.data.MandopopDatabase
import com.mandopop.dictionary.DictionaryRepository
import com.mandopop.notification.DueNotifier
import com.mandopop.notification.StatsTail
import com.mandopop.settings.SettingsStore
import com.mandopop.traverse.Segmenter
import com.mandopop.traverse.TraverseSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The daily-briefing orchestrator: gather inputs → code picks → compose → verify → show.
 *
 * Computed lazily on shade-pull (spec.md §4.4/§5): opening the shade is the only moment the
 * sentence is looked at, so that is when it is made — fresh exactly at glance time, ~zero cost
 * otherwise. Results are cached in memory keyed by an input signature; clearing a notification,
 * an event passing, the local day rolling over, or the vocabulary growing regenerates — an
 * unchanged moment does not, and generation is additionally floored at one per five minutes
 * because SystemUI announces unlocks and volume presses too. Nothing is persisted: after
 * process death the briefing is simply absent until the next pull. Failures log loudly
 * (rejections included) — this device's only debugger is logcat.
 */
object BriefingEngine {
    private const val TAG = "MandopopBriefing"

    /** SystemUI announces the shade, the keyguard, volume — a real pull-down at most this often. */
    private const val SHADE_THROTTLE_MS = 20_000L

    private const val MODEL_ROUNDS = 2

    /** Floor between generations, independent of input churn — the battery knob. */
    private const val REGEN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    private const val SCORE_FRESH_MS = 15 * 60 * 1000L

    enum class Source { MODEL, TEMPLATE }

    /**
     * The notification shows [sentence] hanzi-only — no glosses, ever. The introduction-rule
     * gloss for the frontier word was tried and killed: `known_words` mirrors Traverse, and
     * this user's Chinese predates the course, so "un-learned by the deck" routinely meant
     * "known to the human" — and an English gloss beside a recallable word wrecks the recall.
     * A frontier word may still appear in-sentence, unglossed (noticing without the answer).
     */
    data class Briefing(
        val sentence: String,
        val source: Source,
        val generatedAtMs: Long,
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

    private val mutex = Mutex()
    private var lastSignature: Int? = null

    private var composer: LlamaComposer? = null
    private var composerKey: String? = null

    @Volatile
    private var lastShadeMs = 0L

    /** Generation stamp of an ambient (zero-due) briefing the user swiped away. */
    @Volatile
    private var dismissedGenerationMs = 0L

    /** Rendered score line + snapshot timestamp — spec.md §4.4's comprehension line. */
    @Volatile
    private var storedScore: Pair<String, Long>? = null

    /**
     * "How much of the screen I was just reading do I comprehend" — readable-% for Chinese
     * screens, sayable-% for English ones — only while the snapshot it was scored from is
     * recent enough to still be that screen.
     */
    val screenScoreLine: String?
        get() = storedScore
            ?.takeIf { System.currentTimeMillis() - it.second < SCORE_FRESH_MS }
            ?.first

    /**
     * One llama.cpp composer per process, rebuilt when the model file's identity changes —
     * name, mtime and size, so a re-push reloads (and a transient init failure gets a fresh
     * chance) instead of stale weights staying silently resident. llama.cpp mmaps the file, so
     * overwriting a *currently loaded* model in place is still undefined until this rescan
     * runs; push under a new name when in doubt. The previous runtime is closed first.
     */
    @Synchronized
    fun composerFor(context: Context): LlamaComposer {
        val appContext = context.applicationContext
        // The app must own this dir: one created by `adb shell mkdir` belongs to `shell` and
        // the app's uid cannot traverse it — the files look missing while sitting right there.
        val dir = File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }
        val gguf = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.minByOrNull { it.name }
        val key = gguf?.let { "${it.name}:${it.lastModified()}:${it.length()}" } ?: "none"
        if (key != composerKey) {
            composer?.close()
            composer = LlamaComposer(appContext)
            composerKey = key
        }
        return composer!!
    }

    /**
     * The briefing the zero-due ambient line may show: none if the user dismissed this exact
     * generation. Reposts come from many doors (worker, resume, shade-pull) and must not
     * resurrect a dismissed line; a *newly generated* briefing shows again.
     */
    fun ambientBriefing(): Briefing? = current?.takeIf { it.generatedAtMs != dismissedGenerationMs }

    fun ambientDismissed() {
        current?.let { dismissedGenerationMs = it.generatedAtMs }
    }

    /**
     * Releases everything the feature holds — the resident model (~1.6GB), caches, signature —
     * so the toggle is a real power switch, not a display filter. Cheap to call repeatedly.
     */
    @Synchronized
    fun onDisabled() {
        composer?.close()
        composer = null
        composerKey = null
        stored = null
        storedScore = null
        lastSignature = null
    }

    /** Shade-pull entry point, called from the accessibility service on SystemUI window events. */
    fun shadePulled(context: Context, scope: CoroutineScope) {
        if (!SettingsStore(context).snapshot().briefingEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastShadeMs < SHADE_THROTTLE_MS) return
        lastShadeMs = now
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Default) {
            try {
                // The score is glance-time data about *this* screen — it must recompute and
                // repost even when the briefing's inputs haven't moved, which is why it lives
                // outside the signature cache.
                val scoreChanged = runCatching { refreshScreenScore(appContext) }
                    .getOrDefault(false)
                val briefingChanged = refresh(appContext)
                // One sync, one example resolution — for the notification display only. The
                // briefing is deliberately not bent around the due word: weaving a random SRS
                // word into "your day" produced 今天你要选择家-grade sentences, and recall
                // belongs to the course-authored cloze and Reveal, not the composed line.
                val sync = TraverseSync(appContext)
                val signedIn = sync.isSignedIn()
                val example = if (signedIn) runCatching { sync.localExample() }.getOrNull() else null
                // Stats move with the clock even when the briefing inputs don't.
                runCatching { StatsTail.refresh(appContext) }
                if ((scoreChanged || briefingChanged) && signedIn) {
                    DueNotifier.showLocal(
                        appContext,
                        sync.localDueCount(),
                        sync.localLiveCount(),
                        example,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "shade-pull briefing refresh failed", error)
            }
        }
    }

    /**
     * Rescores the current screen snapshot: readable-% for Chinese screens, sayable-% for
     * English ones. Returns whether the rendered line changed.
     */
    suspend fun refreshScreenScore(context: Context): Boolean =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val snap = ScreenTextMonitor.snapshot
                    ?.takeIf { System.currentTimeMillis() - it.capturedAtMs < SCORE_FRESH_MS }
                    ?: return@withLock false
                val database = MandopopDatabase.get(context)
                val known = database.frontierDao().knownHanzi().toHashSet()
                if (known.isEmpty()) return@withLock false
                val dictionary = DictionaryRepository.shared(context)
                val snapWords = dictionary.knownSimplified(Segmenter.candidates(snap.text))
                val score = ScreenScoring.readable(
                    snap.text,
                    isWord = { it in snapWords || it in known },
                    isKnown = { it in known },
                ) ?: ScreenScoring.sayable(
                    snap.text,
                    isKnown = { it in known },
                    lookup = { word -> dictionary.lookup(word, 1).firstOrNull()?.simplified },
                ) ?: return@withLock false
                val line = ScreenScoring.line(score)
                val changed = storedScore?.first != line
                storedScore = line to snap.capturedAtMs
                changed
            }
        }

    /**
     * Regenerates if the inputs moved (or [force]). Returns whether anything changed — callers
     * repost the notification only on true, so an idle shade-pull never flickers it. Hops to
     * [Dispatchers.Default] itself: the calendar provider query and shade snapshot must not
     * run on main.
     */
    suspend fun refresh(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            mutex.withLock { refreshLocked(context, force) }
        }

    private suspend fun refreshLocked(context: Context, force: Boolean): Boolean {
        if (!SettingsStore(context).snapshot().briefingEnabled) return false
        // Generation is the battery cost — SystemUI announces volume presses and every unlock,
        // not just shades, and an active phone's notification set churns enough that the input
        // signature alone regenerated dozens of times a day (audited at ~5-8% of the battery).
        // Today's briefing staying put for a few minutes is fine; force bypasses.
        stored?.let {
            if (!force && sameLocalDay(it.generatedAtMs) &&
                System.currentTimeMillis() - it.generatedAtMs < REGEN_MIN_INTERVAL_MS
            ) {
                return false
            }
        }
        val inputs = BriefingInputs(
            nowMs = System.currentTimeMillis(),
            events = CalendarSource.eventsRemainingToday(context),
            notifications = NotificationCatcher.activeNotifications(),
            screen = ScreenTextMonitor.snapshot,
        )
        val database = MandopopDatabase.get(context)
        // The signature folds in the local date (yesterday's "今天…" must not survive the
        // rollover just because the shade looks the same) and the vocabulary size (the
        // no-known-words outcome must retry once a sync has landed, not wait for an input
        // to move).
        val knownCount = database.knownWordDao().count()
        val today = LocalDate.now(ZoneId.systemDefault())
        val signature = (inputs.signature() * 31 + knownCount) * 31 + today.hashCode()
        if (!force && signature == lastSignature) return false

        val known = database.frontierDao().knownHanzi().toHashSet()
        if (known.isEmpty()) {
            finish(signature, null, "no known words yet — sign in and sync first", emptyList())
            return true
        }
        // A word can sit on two cards — one live (so it is known) and one still-suspended (so
        // it matches the frontier query). The frontier's whole meaning is "un-learned"; the
        // filter keeps the prompt's introduction slot honest.
        val frontier = database.frontierDao().frontierWords().filterNot { it.hanzi in known }

        val rejections = mutableListOf<String>()
        val dictionary = DictionaryRepository.shared(context)

        val plan = BriefingPicker.plan(inputs, known, frontier, ZoneId.systemDefault()) { word ->
            dictionary.lookup(word, 1).firstOrNull()?.simplified
        }
        if (plan == null) {
            finish(signature, null, "no relevant known vocabulary in today's inputs", emptyList())
            return true
        }

        // Logcat is the record of what the sentence was composed FROM — without it, "why does
        // it keep saying home" is undiagnosable.
        Log.i(TAG, "composing: gist=\"${plan.gist}\" words=${plan.words}")

        suspend fun verdictOf(sentence: String) =
            verdict(sentence, known, plan.frontier?.hanzi, dictionary)

        var result: Briefing? = null

        val model = composerFor(context)
        val modelStatus = model.status()
        if (modelStatus is ComposerStatus.MissingModel) {
            rejections += "model not installed — adb push to ${modelStatus.expectedPath}"
        } else {
            if (modelStatus is ComposerStatus.Failed) {
                // Noted but not terminal: generate() re-attempts the load, so a transient
                // init failure (driver refusal at boot, memory pressure mid-load) cannot
                // latch the model off for the process lifetime behind a silent template.
                rejections += "previous engine failure, retrying: ${modelStatus.message}"
            }
            var avoid = emptyList<String>()
            for (round in 0 until MODEL_ROUNDS) {
                val raw = try {
                    model.generate(BriefingPrompt.build(plan.gist, plan.words, avoid))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    rejections += "model error: ${error.message ?: error::class.simpleName}"
                    break
                }
                val sentence = BriefingPrompt.extractSentence(raw)
                when (val verdict = verdictOf(sentence)) {
                    is BriefingVerifier.Verdict.Pass -> {
                        result = Briefing(sentence, Source.MODEL, inputs.nowMs)
                        break
                    }
                    is BriefingVerifier.Verdict.Fail -> {
                        rejections += "model: ${verdict.reason}"
                        avoid = (avoid + verdict.unknownWords).distinct().take(6)
                    }
                }
            }
        }

        if (result == null) {
            for (candidate in TemplateComposer.candidates(plan)) {
                when (val verdict = verdictOf(candidate)) {
                    is BriefingVerifier.Verdict.Pass -> {
                        result = Briefing(candidate, Source.TEMPLATE, inputs.nowMs)
                        break
                    }
                    is BriefingVerifier.Verdict.Fail ->
                        rejections += "template \"$candidate\": ${verdict.reason}"
                }
            }
        }

        finish(
            signature,
            result,
            when (result?.source) {
                Source.MODEL -> "model composed it"
                Source.TEMPLATE -> "template fallback"
                null -> "nothing verified — no briefing shown"
            },
            rejections,
        )
        return true
    }

    private suspend fun verdict(
        sentence: String,
        known: Set<String>,
        frontierHanzi: String?,
        dictionary: DictionaryRepository,
    ): BriefingVerifier.Verdict {
        val allowed: (String) -> Boolean = { it in known || it == frontierHanzi }
        val dictWords = dictionary.knownSimplified(Segmenter.candidates(sentence))
        return BriefingVerifier.verify(
            sentence,
            isWord = { it in dictWords || allowed(it) },
            isAllowed = allowed,
        )
    }

    private fun sameLocalDay(thenMs: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate() ==
            LocalDate.now(zone)
    }

    private fun finish(
        signature: Int,
        briefing: Briefing?,
        outcome: String,
        rejections: List<String>,
    ) {
        lastSignature = signature
        stored = briefing
        // Loud on purpose: logcat is this device's only debugger, and a silently absent
        // briefing is indistinguishable from a broken one.
        if (briefing == null) {
            Log.w(TAG, "no briefing: $outcome; rejections=$rejections")
        } else {
            Log.i(TAG, "briefing [$outcome] ${briefing.sentence}" +
                if (rejections.isEmpty()) "" else " (after $rejections)")
        }
    }
}
