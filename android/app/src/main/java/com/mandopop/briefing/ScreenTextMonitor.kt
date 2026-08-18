package com.mandopop.briefing

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Rolling in-memory copy of the foreground app's rendered text.
 *
 * Needed because comprehensibility-at-glance is evaluated when the shade opens, and by then the
 * active window *is* the shade — the snapshot has to already exist. The accessibility view tree
 * gives rendered text directly (no OCR anywhere), the same source the lookup service already
 * reads. Captures are throttled by the caller; the walk itself is capped so a pathological tree
 * cannot stall the service. Never persisted, never leaves the process.
 */
object ScreenTextMonitor {
    const val MIN_CAPTURE_INTERVAL_MS = 3_000L
    private const val MAX_NODES = 500
    private const val MAX_CHARS = 4_000

    @Volatile
    var snapshot: ScreenSnapshot? = null
        private set

    @Volatile
    private var lastCaptureMs = 0L

    fun shouldCapture(nowMs: Long): Boolean = nowMs - lastCaptureMs >= MIN_CAPTURE_INTERVAL_MS

    fun capture(root: AccessibilityNodeInfo?, packageName: String?, nowMs: Long) {
        if (root == null || packageName == null) return
        lastCaptureMs = nowMs

        val pieces = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES && pieces.length < MAX_CHARS) {
            val node = queue.poll() ?: continue
            visited++
            // Passwords never enter the snapshot; invisible nodes carry recycler-view leftovers.
            if (node.isPassword || !node.isVisibleToUser) continue
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (pieces.isNotEmpty()) pieces.append('\n')
                pieces.append(it.take(MAX_CHARS - pieces.length))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }

        if (pieces.isNotEmpty()) {
            snapshot = ScreenSnapshot(packageName, pieces.toString(), nowMs)
        }
    }
}
