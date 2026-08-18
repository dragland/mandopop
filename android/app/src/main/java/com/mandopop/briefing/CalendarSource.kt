package com.mandopop.briefing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.mandopop.data.DueCounter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What remains of today, straight from the calendar provider.
 *
 * Queried at generation time and never stored — the calendar app is the database. Ungranted
 * permission degrades to "no events", not an error: the briefing simply draws on the other
 * inputs. Three provider gotchas are handled explicitly: hidden calendars still return rows
 * (VISIBLE is a column, not a filter), declined invitations still return rows (the provider
 * never filters them — SELF_ATTENDEE_STATUS exists precisely because of that), and all-day
 * events are stored at *UTC* midnight, so their epoch range trails local wall-clock in
 * UTC-negative zones — compared as UTC dates, and the query window reaches back a day so the
 * provider's overlap test cannot drop them in the evening.
 */
object CalendarSource {
    private const val TAG = "MandopopBriefing"
    private const val MAX_EVENTS = 10
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun eventsRemainingToday(context: Context): List<CalendarEvent> {
        // Inline rather than extracted, same reason as the notification's permission check: lint
        // cannot follow a check through a helper.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val endOfDay = DueCounter.endOfDayMs(now, zone)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.VISIBLE,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )

        return try {
            val events = mutableListOf<CalendarEvent>()
            CalendarContract.Instances
                .query(context.contentResolver, projection, now - DAY_MS, endOfDay)
                ?.use { cursor ->
                    val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                    val visibleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.VISIBLE)
                    val statusIdx = cursor.getColumnIndexOrThrow(
                        CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                    )
                    while (cursor.moveToNext() && events.size < MAX_EVENTS) {
                        if (cursor.getInt(visibleIdx) == 0) continue
                        if (cursor.getInt(statusIdx) ==
                            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                        ) {
                            continue
                        }
                        val title = cursor.getString(titleIdx)?.trim().orEmpty()
                        if (title.isEmpty()) continue
                        val begin = cursor.getLong(beginIdx)
                        val end = cursor.getLong(endIdx)
                        val allDay = cursor.getInt(allDayIdx) != 0
                        if (allDay) {
                            // UTC-midnight range → the local dates it covers; end is exclusive.
                            val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                            val lastExclusive = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                            if (today < first || today >= lastExclusive) continue
                        } else {
                            if (end < now || begin > endOfDay) continue
                        }
                        events += CalendarEvent(title = title, beginMs = begin, endMs = end, allDay = allDay)
                    }
                }
            // Timed events first: a 3pm meeting is more salient than an all-day banner, and the
            // picker takes the head of this list.
            events.sortedWith(compareBy({ it.allDay }, { it.beginMs }))
        } catch (error: Exception) {
            Log.e(TAG, "Calendar query failed", error)
            emptyList()
        }
    }
}
