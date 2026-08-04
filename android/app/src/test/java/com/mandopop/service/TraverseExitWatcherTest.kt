package com.mandopop.service

import com.mandopop.service.TraverseExitWatcher.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class TraverseExitWatcherTest {
    private val watcher = TraverseExitWatcher()
    private val traverse = TraverseExitWatcher.TRAVERSE_PACKAGE

    @Test
    fun `leaving Traverse for another app schedules a sync`() {
        assertEquals(Action.CANCEL_PENDING, watcher.onForegroundPackage(traverse))
        assertEquals(Action.SCHEDULE_SYNC, watcher.onForegroundPackage("com.android.chrome"))
    }

    @Test
    fun `never having been in Traverse does not schedule anything`() {
        assertEquals(Action.IGNORE, watcher.onForegroundPackage("com.android.chrome"))
        assertEquals(Action.IGNORE, watcher.onForegroundPackage("com.instagram.android"))
    }

    @Test
    fun `pulling the shade over Traverse is not leaving`() {
        watcher.onForegroundPackage(traverse)
        assertEquals(Action.IGNORE, watcher.onForegroundPackage("com.android.systemui"))
        // Still considered inside, so the shade closing is just a re-entry.
        assertEquals(Action.CANCEL_PENDING, watcher.onForegroundPackage(traverse))
    }

    @Test
    fun `the keyboard and our own overlay are not leaving`() {
        watcher.onForegroundPackage(traverse)
        assertEquals(
            Action.IGNORE,
            watcher.onForegroundPackage("com.google.android.inputmethod.latin"),
        )
        assertEquals(Action.IGNORE, watcher.onForegroundPackage("com.mandopop"))
    }

    @Test
    fun `returning to Traverse cancels a pending sync`() {
        watcher.onForegroundPackage(traverse)
        assertEquals(Action.SCHEDULE_SYNC, watcher.onForegroundPackage("com.android.chrome"))
        assertEquals(Action.CANCEL_PENDING, watcher.onForegroundPackage(traverse))
    }

    @Test
    fun `leaving fires once, not repeatedly as other apps come forward`() {
        watcher.onForegroundPackage(traverse)
        assertEquals(Action.SCHEDULE_SYNC, watcher.onForegroundPackage("com.android.chrome"))
        assertEquals(Action.IGNORE, watcher.onForegroundPackage("com.instagram.android"))
        assertEquals(Action.IGNORE, watcher.onForegroundPackage("com.android.chrome"))
    }

    @Test
    fun `blank package names are ignored`() {
        watcher.onForegroundPackage(traverse)
        assertEquals(Action.IGNORE, watcher.onForegroundPackage(null))
        assertEquals(Action.IGNORE, watcher.onForegroundPackage(""))
        // The earlier Traverse state must survive a junk event.
        assertEquals(Action.SCHEDULE_SYNC, watcher.onForegroundPackage("com.android.chrome"))
    }
}
