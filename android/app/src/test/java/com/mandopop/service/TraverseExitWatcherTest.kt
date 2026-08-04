package com.mandopop.service

import com.mandopop.service.TraverseExitWatcher.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class TraverseExitWatcherTest {
    private val watcher = TraverseExitWatcher()
    private val traverse = TraverseExitWatcher.TRAVERSE_PACKAGE

    @Test
    fun `leaving Traverse for another app schedules a sync`() {
        assertEquals(Action.IGNORE, watcher.onForegroundPackage(traverse))
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
        // Still considered inside, so closing the shade is a no-op rather than a fresh exit.
        assertEquals(Action.IGNORE, watcher.onForegroundPackage(traverse))
        assertEquals(Action.SCHEDULE_SYNC, watcher.onForegroundPackage("com.android.chrome"))
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
    fun `a Traverse window event after leaving never revokes the scheduled sync`() {
        // Traverse re-announces its window about a second after being backgrounded, on every
        // exit. Reading that as "the user came back" cancelled the settle timer every time, so
        // the count only ever refreshed on the periodic run. Whether the user is really back is
        // settled by the caller checking the active window when the timer fires, not here.
        watcher.onForegroundPackage(traverse)
        assertEquals(Action.SCHEDULE_SYNC, watcher.onForegroundPackage("com.android.chrome"))
        assertEquals(Action.IGNORE, watcher.onForegroundPackage(traverse))
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
