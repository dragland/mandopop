package com.mandopop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers the day boundary only. The row filter (`suspended = 0 AND due_time_ms < boundary`) lives
 * once, in `ScheduleDao`; see [DueCounter] for why it is not duplicated here.
 */
class DueCounterTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val utc = ZoneId.of("UTC")

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `boundary is midnight at the end of the local day`() {
        // 2026-08-04T00:00 PDT == 2026-08-04T07:00Z
        assertEquals(
            at("2026-08-04T07:00:00Z"),
            DueCounter.endOfDayMs(LocalDate.of(2026, 8, 3), la),
        )
    }

    @Test
    fun `boundary derived from an instant uses that instant's local day`() {
        // 20:00 PDT on 2026-08-02 is already 2026-08-03 in UTC; the local day must win.
        assertEquals(
            at("2026-08-03T07:00:00Z"),
            DueCounter.endOfDayMs(at("2026-08-03T03:00:00Z"), la),
        )
    }

    @Test
    fun `a card due early tomorrow UTC is still inside today's local boundary`() {
        // The case that makes "due now" the wrong rule: at 20:00 local nothing is due yet, but a
        // card comes due before the local day rolls over.
        val boundary = DueCounter.endOfDayMs(at("2026-08-03T03:00:00Z"), la)
        assertTrue(at("2026-08-03T06:00:00Z") < boundary)
    }

    @Test
    fun `cards past local midnight fall outside`() {
        val boundary = DueCounter.endOfDayMs(at("2026-08-03T18:00:00Z"), la)
        assertTrue("23:59 local is today", at("2026-08-04T06:59:00Z") < boundary)
        assertTrue("00:01 local is tomorrow", at("2026-08-04T07:01:00Z") >= boundary)
    }

    @Test
    fun `zone changes which day an instant belongs to`() {
        val now = at("2026-08-03T02:00:00Z") // 19:00 PDT Aug 2, but already Aug 3 in UTC
        assertEquals(at("2026-08-04T00:00:00Z"), DueCounter.endOfDayMs(now, utc))
        assertEquals(at("2026-08-03T07:00:00Z"), DueCounter.endOfDayMs(now, la))
    }

    @Test
    fun `boundary follows DST rather than assuming 24-hour days`() {
        // 2026-11-01 is the PDT->PST fallback, so that local day is 25 hours long.
        val start = LocalDate.of(2026, 11, 1).atStartOfDay(la).toInstant().toEpochMilli()
        val end = DueCounter.endOfDayMs(LocalDate.of(2026, 11, 1), la)
        assertEquals(25 * 60 * 60 * 1000L, end - start)
    }
}
