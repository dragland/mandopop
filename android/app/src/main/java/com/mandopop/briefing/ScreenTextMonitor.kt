package com.mandopop.briefing

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Rolling in-memory copy of the foreground app's rendered text.
 *
 * Needed because comprehensibility-at-glance is evaluated when the shade opens, and by then the
 * active window *is* the shade — the snapshot has to already exist. The accessibility view tree
 * gives rendered text directly (no OCR anywhere), the same source the lookup service already
 * reads. Never persisted, never leaves the process.
 *
 * Threading contract: [tryClaim] is the cheap main-thread gate (volatile read + subtraction);
 * the walk itself is binder-per-node and belongs on a background dispatcher —
 * `AccessibilityNodeInfo` getters are plain IPC with no main-thread requirement. The caller
 * fetches `rootInActiveWindow` at event time (one call, before the window goes stale) and hands
 * it over.
 */
object ScreenTextMonitor {
    const val MIN_CAPTURE_INTERVAL_MS = 3_000L
    private const val MAX_NODES = 150
    private const val MAX_CHARS = 4_000
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    @Volatile
    var snapshot: ScreenSnapshot? = null
        private set

    @Volatile
    private var lastCaptureMs = 0L

    /** Read-only pre-gate: a volatile read and a subtraction, nothing else. */
    fun shouldCapture(nowMs: Long): Boolean = nowMs - lastCaptureMs >= MIN_CAPTURE_INTERVAL_MS

    /**
     * Claims the next capture slot. Claiming *before* launching the walk keeps the throttle
     * honest — otherwise every content-change event in the 3s window would launch its own walk
     * while the first was still in flight.
     */
    fun tryClaim(nowMs: Long): Boolean {
        if (nowMs - lastCaptureMs < MIN_CAPTURE_INTERVAL_MS) return false
        lastCaptureMs = nowMs
        return true
    }

    fun capture(root: AccessibilityNodeInfo?, packageName: String?, nowMs: Long) {
        if (root == null || packageName == null) return
        lastCaptureMs = nowMs

        // The event's package and the active window can disagree: a background app fires a
        // content change while the shade or another app holds the screen. Walking that tree
        // would label one window's text with another app's name — the shade's contents as
        // "what the user is reading" in the worst case. The root's own package is the truth.
        val rootPackage = root.packageName?.toString()
        if (rootPackage != packageName || rootPackage == SYSTEM_UI_PACKAGE) return

        val pieces = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            if (visited >= MAX_NODES || pieces.length >= MAX_CHARS) {
                // Early exit still recycles what was queued but never visited.
                if (node !== root) recycleQuietly(node)
                continue
            }
            visited++
            // Passwords never enter the snapshot; invisible nodes carry recycler-view leftovers.
            if (!node.isPassword && node.isVisibleToUser) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    if (pieces.isNotEmpty()) pieces.append('\n')
                    pieces.append(it.take(MAX_CHARS - pieces.length))
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            }
            // Pre-API-33 devices pool these objects (minSdk is 26); the root stays with the
            // service, everything obtained via getChild is ours to return — same convention as
            // the recycle() calls in TextSelectionService.
            if (node !== root) recycleQuietly(node)
        }

        if (pieces.isNotEmpty()) {
            snapshot = ScreenSnapshot(packageName, pieces.toString(), nowMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (ignored: IllegalStateException) {
            // Already recycled by the system; nothing to do.
        }
    }
}
