package com.mandopop.notification

import android.content.Context
import com.mandopop.data.MandopopDatabase
import com.mandopop.traverse.StudyStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The expanded view's stats line (spec.md §4.3), cached in memory and recomputed at the moments
 * the notification is actually rebuilt — sync, app resume, shade-pull. Day-gated like the
 * briefing: yesterday's "min studied" must not survive midnight. Nothing persists; the mirror
 * and UsageStats are re-queried each time.
 */
object StatsTail {

    @Volatile
    private var stored: Pair<String, Long>? = null

    val line: String?
        get() = stored?.takeIf { sameLocalDay(it.second) }?.first

    suspend fun refresh(context: Context) {
        val rows = MandopopDatabase.get(context).scheduleDao().liveIntervals()
        if (rows.isEmpty()) {
            stored = null
            return
        }
        val now = System.currentTimeMillis()
        val stats = StudyStats.compute(rows, now)
        stored = StudyStats.line(stats, UsageMinutes.today(context)) to now
    }

    private fun sameLocalDay(thenMs: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }
}
