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

    /** One line, " · "-joined — the expanded view has no room for a dashboard. */
    fun line(stats: Stats, minutesStudied: Int?): String = buildString {
        append("≈${stats.recallable} recallable now · ${stats.mature} mature / ${stats.young} young")
        if (minutesStudied != null && minutesStudied > 0) {
            append(" · $minutesStudied min studied")
        }
    }
}
