package com.mandopop.briefing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.mandopop.data.DueCounter
import java.time.ZoneId

/**
 * What remains of today, straight from the calendar provider.
 *
 * Queried at generation time and never stored — the calendar app is the database. Ungranted
 * permission degrades to "no events", not an error: the briefing simply draws on the other inputs.
 */
object CalendarSource {
    private const val TAG = "MandopopBriefing"
    private const val MAX_EVENTS = 10

    fun eventsRemainingToday(context: Context): List<CalendarEvent> {
        // Inline rather than extracted, same reason as the notification's permission check: lint
        // cannot follow a check through a helper.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val endOfDay = DueCounter.endOfDayMs(now, ZoneId.systemDefault())
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )

        return try {
            val events = mutableListOf<CalendarEvent>()
            CalendarContract.Instances.query(context.contentResolver, projection, now, endOfDay)
                ?.use { cursor ->
                    val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                    while (cursor.moveToNext() && events.size < MAX_EVENTS) {
                        val title = cursor.getString(titleIdx)?.trim().orEmpty()
                        if (title.isEmpty()) continue
                        val end = cursor.getLong(endIdx)
                        if (end < now) continue
                        events += CalendarEvent(
                            title = title,
                            beginMs = cursor.getLong(beginIdx),
                            endMs = end,
                            allDay = cursor.getInt(allDayIdx) != 0,
                        )
                    }
                }
            events.sortedBy { it.beginMs }
        } catch (error: Exception) {
            Log.e(TAG, "Calendar query failed", error)
            emptyList()
        }
    }
}
