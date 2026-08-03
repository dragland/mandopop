package com.mandopop.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TextSelectionService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: kotlinx.coroutines.Job? = null
    private val exitWatcher = TraverseExitWatcher()
    private var exitSyncJob: kotlinx.coroutines.Job? = null

    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var overlayManager: OverlayManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var ttsManager: ChineseTtsManager

    override fun onCreate() {
        super.onCreate()
        dictionaryRepository = DictionaryRepository(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        ttsManager = ChineseTtsManager(applicationContext)
        overlayManager = OverlayManager(this, ttsManager)
        serviceScope.launch {
            dictionaryRepository.warmUp()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleForegroundChange(event.packageName?.toString())
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return

        debounceJob?.cancel()
        val settings = settingsStore.snapshot()
        if (!settings.enabled) {
            overlayManager.dismiss()
            return
        }

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
        serviceScope.cancel()
        overlayManager.dismiss()
        ttsManager.shutdown()
        dictionaryRepository.close()
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
        // Window changes fire on every app switch, so only the two interesting outcomes are
        // logged — tracing every IGNORE would bury the signal.
        if (action != TraverseExitWatcher.Action.IGNORE) {
            Log.i(TAG, "foreground=$packageName -> $action")
        }
        when (action) {
            TraverseExitWatcher.Action.IGNORE -> Unit
            TraverseExitWatcher.Action.CANCEL_PENDING -> exitSyncJob?.cancel()
            TraverseExitWatcher.Action.SCHEDULE_SYNC -> {
                exitSyncJob?.cancel()
                exitSyncJob = serviceScope.launch {
                    delay(EXIT_SETTLE_MS)
                    SyncWorker.syncNow(applicationContext)
                }
            }
        }
    }

    private suspend fun showLookup(text: String) {
        val settings = settingsStore.snapshot()
        if (!settings.enabled) {
            overlayManager.dismiss()
            return
        }

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

        /** Grace period before treating a departure from Traverse as final. */
        private const val EXIT_SETTLE_MS = 2_000L
    }
}
