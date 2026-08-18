package com.mandopop.notification

import android.content.Context
import com.mandopop.data.MandopopDatabase
import com.mandopop.dictionary.DictionaryRepository
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

    /** Process-lifetime handle, same pattern as the engine's — reopening per refresh is waste. */
    @Volatile
    private var dictionary: DictionaryRepository? = null

    val line: String?
        get() = stored?.takeIf { sameLocalDay(it.second) }?.first

    suspend fun refresh(context: Context) {
        val database = MandopopDatabase.get(context)
        val rows = database.scheduleDao().liveIntervals()
        if (rows.isEmpty()) {
            stored = null
            return
        }
        val now = System.currentTimeMillis()
        val stats = StudyStats.compute(rows, now)
        val dictionary = dictionary
            ?: DictionaryRepository(context.applicationContext).also { dictionary = it }
        val (knownMass, totalMass) = dictionary
            .frequencyCoverage(database.frontierDao().knownHanzi())
        val coverage = if (totalMass > 0) knownMass / totalMass * 100.0 else null
        stored = StudyStats.line(stats, UsageMinutes.today(context), coverage) to now
    }

    private fun sameLocalDay(thenMs: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }
}
