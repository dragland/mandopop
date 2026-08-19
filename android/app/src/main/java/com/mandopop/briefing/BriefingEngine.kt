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
 * Computed lazily on shade-pull: fresh at glance time, ~zero cost otherwise. In-memory caches
 * only, keyed by an input signature (inputs, local date, vocabulary size); generation is also
 * floored at one per five minutes because SystemUI window events include unlocks and volume
 * presses, not just shades. Nothing persists; failures log loudly — logcat is this device's
 * only debugger.
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
     * Hanzi-only, no glosses: `known_words` mirrors Traverse and this user's Chinese predates
     * the course, so "un-learned by the deck" often means "recallable by the human" — an
     * English gloss there wrecks recall. A frontier word may appear in-sentence, unglossed.
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
     * One composer per process, rebuilt when the model file identity (name+mtime+size)
     * changes, so a re-push reloads and a transient init failure retries. llama.cpp mmaps the
     * file — overwriting a loaded model in place is undefined; push under a new name.
     */
    @Synchronized
    fun composerFor(context: Context): LlamaComposer {
        val appContext = context.applicationContext
        // App-created so the app's uid owns it: a shell-made dir is untraversable and the
        // files inside look missing.
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

    /** The toggle's power switch: frees the resident model and all caches. Idempotent. */
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
                // Glance-time data about *this* screen — recomputed outside the signature cache.
                val scoreChanged = runCatching { refreshScreenScore(appContext) }
                    .getOrDefault(false)
                val briefingChanged = refresh(appContext)
                // Example resolved once, for display only — the briefing is never bent around
                // the due word; recall belongs to the cloze and Reveal.
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

    /** Rescores the snapshot (readable-% or sayable-%); returns whether the line changed. */
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
     * Regenerates if inputs moved (or [force]); returns whether anything changed so callers
     * only repost on true. Hops to Default itself — the calendar query must not run on main.
     */
    suspend fun refresh(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            mutex.withLock { refreshLocked(context, force) }
        }

    private suspend fun refreshLocked(context: Context, force: Boolean): Boolean {
        if (!SettingsStore(context).snapshot().briefingEnabled) return false
        // Generation is the battery cost; the signature alone churns dozens of times a day.
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
        // Date in the signature: yesterday's 今天 must not survive rollover. Vocab count in:
        // the no-known-words outcome must retry once a sync lands.
        val knownCount = database.knownWordDao().count()
        val today = LocalDate.now(ZoneId.systemDefault())
        val signature = (inputs.signature() * 31 + knownCount) * 31 + today.hashCode()
        if (!force && signature == lastSignature) return false

        val known = database.frontierDao().knownHanzi().toHashSet()
        if (known.isEmpty()) {
            finish(signature, null, "no known words yet — sign in and sync first", emptyList())
            return true
        }
        // A word can be on a live card (known) and a suspended one (frontier row) at once;
        // frontier means un-learned, so subtract.
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

        // The record of what the sentence was composed FROM — sole diagnosability.
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
                // Not terminal: generate() re-attempts the load, so a transient init failure
                // cannot latch the model off behind a silent template.
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
        // A silently absent briefing is indistinguishable from a broken one.
        if (briefing == null) {
            Log.w(TAG, "no briefing: $outcome; rejections=$rejections")
        } else {
            Log.i(TAG, "briefing [$outcome] ${briefing.sentence}" +
                if (rejections.isEmpty()) "" else " (after $rejections)")
        }
    }
}
