package com.mandopop.traverse

import com.mandopop.data.LiveInterval
import kotlin.math.pow

/**
 * The stats tail's math (spec.md §4.3) — pure over mirrored SRS fields.
 *
 * Retrievability is FSRS-style: each live card contributes `0.9^(elapsed/interval)`, treating
 * the SM-2 interval as stability at 90% target retention — a mild assumption, worst case
 * slightly optimistic. The sum reads as "words recallable right now", which moves with the
 * clock in a way a raw card count never does. Mature is Anki's convention: interval ≥ 21 days,
 * honest-arbitrary.
 */
object StudyStats {

    data class Stats(val recallable: Int, val mature: Int, val young: Int)

    private const val DAY_MS = 24 * 60 * 60 * 1000.0
    private const val MATURE_DAYS = 21.0

    fun compute(rows: List<LiveInterval>, nowMs: Long): Stats {
        var recallable = 0.0
        var mature = 0
        for (row in rows) {
            // The last review happened one interval before the due time; elapsed counts from
            // there. Clamped: a card reviewed moments ago is fully retrievable, not >100%.
            val intervalMs = row.intervalDays * DAY_MS
            val elapsedMs = (nowMs - (row.dueTimeMs - intervalMs)).coerceAtLeast(0.0)
            recallable += 0.9.pow(elapsedMs / intervalMs)
            if (row.intervalDays >= MATURE_DAYS) mature++
        }
        return Stats(
            recallable = recallable.toInt(),
            mature = mature,
            young = rows.size - mature,
        )
    }

    /**
     * One line, " · "-joined, in Chinese — chrome the user reads dozens of times a day is free
     * study material. Coverage leads: SUBTLEX frequency mass of the known vocabulary over the
     * whole corpus's mass. Front-loaded by construction (Zipf: this user's top-10 function
     * words carry 26 of their 51 points), so read it as token coverage, never comprehension —
     * Nation's ~98% comfortable-reading threshold is the caveat of record. Wording pattern is
     * shared with the screen line: subject · ≈percent · verb; 日常中文 "everyday Chinese",
     * 还记得 "still remember", 学了…分钟 "studied … minutes".
     */
    fun line(stats: Stats, minutesStudied: Int?, coveragePercent: Double?): String = buildString {
        if (coveragePercent != null && coveragePercent > 0) {
            append("日常中文 ≈%.1f%% 看得懂".format(coveragePercent))
            append(" · ")
        }
        append("还记得 ≈${stats.recallable} 个词")
        if (minutesStudied != null && minutesStudied > 0) {
            append(" · 今天学了 $minutesStudied 分钟")
        }
    }
}
