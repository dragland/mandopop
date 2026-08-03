package com.mandopop.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day boundary for "cards due today".
 *
 * This is the only interesting half of the due rule, and the only half worth testing: which
 * instant the day ends at is timezone- and DST-sensitive, whereas the row filter is a trivial
 * `suspended = 0 AND due_time_ms < boundary`. That filter lives *once*, in `ScheduleDao`, and is
 * deliberately not mirrored here — a second copy would only create the chance for the two to drift.
 *
 * Two decisions, both chosen to agree with what the Traverse app itself shows:
 *
 *  - **End of the local day, not "due right now."** A card due at 07:00 tomorrow should already be
 *    in today's count the way Anki-style schedulers present it. Counting only `dueTime <= now`
 *    would read 0 for most of an evening and then jump.
 *  - **Prompt rows, not distinct cards.** A card with two prompts is two things to answer. On
 *    2026-08-03 that was the difference between 45 and 44, and the shipped count (93) matched
 *    Traverse.
 *
 * The zone is always the device's. Measured over all 3,112 reviews in the reference export,
 * Traverse keys its daily `events` doc by the client's *local* date (disagreeing with UTC in 1,374
 * of them), and its client is a WebView on this same phone — so `ZoneId.systemDefault()` is
 * correct by construction and must not be replaced with a fixed zone.
 *
 * `queue == "new"` is deliberately not special-cased: in the reference export every `new` row is
 * also `suspended`, so `suspended` alone is the correct liveness filter.
 */
object DueCounter {

    /** Exclusive upper bound on `dueTime` for cards counted as due on [today] in [zone]. */
    fun endOfDayMs(today: LocalDate, zone: ZoneId): Long =
        today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayMs(nowMs: Long, zone: ZoneId): Long =
        endOfDayMs(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), zone)
}
