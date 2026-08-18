package com.mandopop.briefing

/**
 * Everything the briefing is a function of, gathered fresh at generation time.
 *
 * Per the purity rule (spec.md §2), none of this is ever persisted: calendar rows, active
 * notifications and the screen snapshot are state the OS already holds, queried at glance time.
 * The one concession is [ScreenSnapshot], which has to be captured *before* the shade opens —
 * once it is open, the active window is the shade — so the accessibility service keeps a rolling
 * in-memory copy of the foreground app's rendered text. Memory only, today-scoped, discardable.
 */
data class CalendarEvent(
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
)

data class ActiveNotification(
    val appLabel: String,
    val title: String,
    val text: String,
    val category: String?,
    val postTimeMs: Long,
)

data class ScreenSnapshot(
    val packageName: String,
    val text: String,
    val capturedAtMs: Long,
)

data class BriefingInputs(
    val nowMs: Long,
    val events: List<CalendarEvent>,
    val notifications: List<ActiveNotification>,
    val screen: ScreenSnapshot?,
) {
    /**
     * Cache key: regenerate only when the day's actual state moves. Clearing a notification or an
     * event ending changes it; screen *text* deliberately does not (it churns with every scroll,
     * which would defeat the cache) — only which app is up.
     */
    fun signature(): Int {
        var hash = events.map { it.title to it.beginMs }.hashCode()
        hash = 31 * hash + notifications.map { Triple(it.appLabel, it.title, it.text) }.hashCode()
        hash = 31 * hash + (screen?.packageName?.hashCode() ?: 0)
        return hash
    }
}
