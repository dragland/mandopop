package com.mandopop.traverse

import com.mandopop.data.LiveInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyStatsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun freshlyReviewedCardIsFullyRetrievable() {
        // Due 10 days out on a 10-day interval means it was reviewed just now.
        val stats = StudyStats.compute(
            listOf(LiveInterval(intervalDays = 10.0, dueTimeMs = now + 10 * day)),
            now,
        )
        assertEquals(1, stats.recallable)
    }

    @Test
    fun cardAtItsDueDateSitsNearNinetyPercent() {
        val rows = (1..100).map { LiveInterval(intervalDays = 10.0, dueTimeMs = now) }
        val stats = StudyStats.compute(rows, now)
        // 100 cards × 0.9 retrievability.
        assertEquals(90, stats.recallable)
    }

    @Test
    fun overdueCardsDecayBelowNinety() {
        val rows = (1..100).map { LiveInterval(intervalDays = 10.0, dueTimeMs = now - 10 * day) }
        val stats = StudyStats.compute(rows, now)
        assertTrue("expected < 90, got ${stats.recallable}", stats.recallable < 90)
    }

    @Test
    fun matureSplitFollowsTheAnkiConvention() {
        val stats = StudyStats.compute(
            listOf(
                LiveInterval(intervalDays = 21.0, dueTimeMs = now + day),
                LiveInterval(intervalDays = 20.9, dueTimeMs = now + day),
            ),
            now,
        )
        assertEquals(1, stats.mature)
        assertEquals(1, stats.young)
    }

    @Test
    fun lineOmitsWhatItCannotHonestlySay() {
        val stats = StudyStats.Stats(recallable = 12, mature = 3, young = 9)
        assertTrue(!StudyStats.line(stats, null, null).contains("min"))
        assertTrue(!StudyStats.line(stats, 0, null).contains("min"))
        assertTrue(!StudyStats.line(stats, null, null).contains("%"))
        assertTrue(StudyStats.line(stats, 38, null).contains("38 min studied"))
    }

    @Test
    fun coverageLeadsTheLine() {
        val stats = StudyStats.Stats(recallable = 12, mature = 3, young = 9)
        val line = StudyStats.line(stats, 38, 41.27)
        assertTrue(line, line.startsWith("≈41.3% of everyday Chinese"))
        assertTrue(line, "mature" !in line && "young" !in line)
    }
}
