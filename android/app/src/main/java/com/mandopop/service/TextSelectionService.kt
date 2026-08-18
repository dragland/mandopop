package com.mandopop.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.mandopop.briefing.BriefingEngine
import com.mandopop.briefing.ScreenTextMonitor
import com.mandopop.dictionary.DictionaryRepository
import com.mandopop.dictionary.Normalizer
import com.mandopop.overlay.NoResultPhrases
import com.mandopop.overlay.OverlayManager
import com.mandopop.settings.SettingsStore
import com.mandopop.tts.ChineseTtsManager
import com.mandopop.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TextSelectionService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: kotlinx.coroutines.Job? = null
    private val exitWatcher = TraverseExitWatcher()
    private var exitSyncJob: kotlinx.coroutines.Job? = null
    private var captureJob: kotlinx.coroutines.Job? = null

    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var overlayManager: OverlayManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var ttsManager: ChineseTtsManager

    override fun onCreate() {
        super.onCreate()
        dictionaryRepository = DictionaryRepository.shared(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        ttsManager = ChineseTtsManager(applicationContext)
        overlayManager = OverlayManager(this, ttsManager)
        serviceScope.launch {
            dictionaryRepository.warmUp()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName == SYSTEM_UI_PACKAGE) {
                // The shade opening is a SystemUI window event — the moment the briefing is
                // actually looked at, so the moment it is computed. Throttling and the input
                // cache live in the engine; volume dialogs and the keyguard cost a no-op.
                if (settingsStore.snapshot().briefingEnabled) {
                    BriefingEngine.shadePulled(applicationContext, serviceScope)
                }
            } else {
                scheduleScreenCapture(packageName)
            }
            handleForegroundChange(packageName)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            maybeCaptureScreen(event)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return

        debounceJob?.cancel()

        if (isPasswordField(event)) {
            overlayManager.dismiss()
            return
        }

        val selectionSpan = abs(event.toIndex - event.fromIndex)
        if (selectionSpan == 0) {
            overlayManager.dismiss()
            return
        }
        if (selectionSpan > Normalizer.MAX_SELECTION_LENGTH) {
            overlayManager.dismiss()
            return
        }

        val selectedText = extractSelectedText(event)
        if (selectedText == null) {
            overlayManager.dismiss()
            return
        }

        if (shouldIgnoreSelection(selectedText)) {
            overlayManager.dismiss()
            return
        }

        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            showLookup(selectedText.trim())
        }
    }

    override fun onInterrupt() {
        debounceJob?.cancel()
        overlayManager.dismiss()
    }

    override fun onDestroy() {
        debounceJob?.cancel()
        exitSyncJob?.cancel()
        captureJob?.cancel()
        serviceScope.cancel()
        overlayManager.dismiss()
        ttsManager.shutdown()
        // The dictionary is the process-shared handle now — closing it here would tear it out
        // from under the briefing engine and every TraverseSync in flight.
        super.onDestroy()
    }

    /**
     * Refreshes the card count shortly after the user leaves Traverse.
     *
     * The settle delay means a quick detour out and back costs nothing, and the shade or keyboard
     * appearing over Traverse is filtered out by the watcher rather than here.
     */
    private fun handleForegroundChange(packageName: String?) {
        val action = exitWatcher.onForegroundPackage(packageName)
        // Window changes fire on every app switch, so only the interesting outcome is logged —
        // tracing every IGNORE would bury the signal.
        if (action != TraverseExitWatcher.Action.IGNORE) {
            Log.i(TAG, "foreground=$packageName -> $action")
        }
        when (action) {
            TraverseExitWatcher.Action.IGNORE -> Unit
            TraverseExitWatcher.Action.SCHEDULE_SYNC -> {
                exitSyncJob?.cancel()
                exitSyncJob = serviceScope.launch {
                    delay(EXIT_SETTLE_MS)
                    // Where the user actually is once the dust settles, rather than what the
                    // window events claimed on the way out. Traverse re-announces its window about
                    // a second after being backgrounded, so cancelling on that signal — which is
                    // what this used to do — killed every pending sync before it could fire.
                    val active = rootInActiveWindow?.packageName?.toString()
                    if (active == TraverseExitWatcher.TRAVERSE_PACKAGE) {
                        Log.i(TAG, "back in Traverse at settle; skipping sync")
                        return@launch
                    }
                    Log.i(TAG, "left Traverse (now $active); syncing")
                    SyncWorker.syncNow(applicationContext)
                }
            }
        }
    }

    /**
     * Rolling snapshot of the foreground app's text, for the shade-pull briefing.
     *
     * Captured *before* the shade opens because once it is open the active window is the shade.
     * A window-state change means a fresh app whose tree needs a moment to settle; content
     * changes are throttled in [ScreenTextMonitor] so a busy page costs one bounded walk every
     * few seconds at most.
     */
    private fun scheduleScreenCapture(packageName: String?) {
        // The rolling snapshot only exists to feed the briefing — off means off.
        if (!settingsStore.snapshot().briefingEnabled) return
        if (!isCapturablePackage(packageName)) return
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            delay(CAPTURE_SETTLE_MS)
            // Root fetched on the service thread — one binder call, before the window goes
            // stale; the multi-node walk hops off-main (node getters are plain IPC, safe there).
            val root = rootInActiveWindow ?: return@launch
            withContext(Dispatchers.Default) {
                ScreenTextMonitor.capture(root, packageName, System.currentTimeMillis())
            }
        }
    }

    /** Cheapest gate first: at notificationTimeout=100 most content-change events must cost
     *  a volatile read and nothing else — not even a packageName string conversion. */
    private fun maybeCaptureScreen(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        if (!ScreenTextMonitor.shouldCapture(now)) return
        if (!settingsStore.snapshot().briefingEnabled) return
        val packageName = event.packageName?.toString()
        if (!isCapturablePackage(packageName)) return
        if (!ScreenTextMonitor.tryClaim(now)) return
        val root = rootInActiveWindow ?: return
        serviceScope.launch(Dispatchers.Default) {
            ScreenTextMonitor.capture(root, packageName, System.currentTimeMillis())
        }
    }

    private fun isCapturablePackage(packageName: String?): Boolean =
        !packageName.isNullOrBlank() &&
            packageName != applicationContext.packageName &&
            packageName != SYSTEM_UI_PACKAGE &&
            !packageName.contains("inputmethod")

    private suspend fun showLookup(text: String) {
        val settings = settingsStore.snapshot()
        val entries = dictionaryRepository.lookup(text)
        if (entries.isNotEmpty()) {
            overlayManager.show(entries, settings, isNoResult = false)
            return
        }

        if (settings.playfulNoResult) {
            overlayManager.show(listOf(NoResultPhrases.random()), settings, isNoResult = true)
        } else {
            overlayManager.dismiss()
        }
    }

    private fun extractSelectedText(event: AccessibilityEvent): String? {
        val from = event.fromIndex
        val to = event.toIndex
        if (from < 0 || to < 0) return null

        val start = min(from, to)
        val end = max(from, to)
        if (start == end) return null

        val eventText = event.text?.firstOrNull()
        eventText?.substringOrNull(start, end)?.let { return it }
        if (eventText != null && eventText.isNotBlank() && eventText.length == end - start) {
            return eventText.toString().trim()
        }

        val source = event.source ?: return null
        return try {
            source.text?.substringOrNull(start, end)
        } finally {
            source.recycle()
        }
    }

    private fun isPasswordField(event: AccessibilityEvent): Boolean {
        val source = event.source ?: return false
        return try {
            source.isPassword
        } finally {
            source.recycle()
        }
    }

    private fun shouldIgnoreSelection(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.length > Normalizer.MAX_SELECTION_LENGTH) return true
        if (trimmed.all { it.isWhitespace() || it.isDigit() }) return true
        return trimmed.any { it in '\u4e00'..'\u9fff' }
    }

    private fun CharSequence.substringOrNull(start: Int, end: Int): String? {
        if (start < 0 || end > length || start >= end) return null
        val value = subSequence(start, end).toString().trim()
        return value.ifBlank { null }
    }

    companion object {
        private const val TAG = "MandopopExit"
        private const val DEBOUNCE_MS = 300L

        /**
         * Grace period before treating a departure from Traverse as final.
         *
         * Also gives Traverse time to flush the day's events document, which is the heartbeat an
         * unforced sync reads to decide whether the deck is worth pulling.
         */
        private const val EXIT_SETTLE_MS = 5_000L

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** A freshly announced window's tree is often still inflating; give it a beat. */
        private const val CAPTURE_SETTLE_MS = 600L
    }
}
