package com.mandopop.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateComposerTest {

    private fun plan(
        kind: BriefingPicker.SourceKind,
        timeOfDay: String? = null,
        topic: String? = null,
    ) = BriefingPicker.Plan(
        kind = kind,
        gist = "test",
        words = listOfNotNull(timeOfDay, topic),
        frontier = null,
        timeOfDay = timeOfDay,
        topic = topic,
    )

    @Test
    fun calendarCandidatesGoFromSpecificToUniversal() {
        val candidates = TemplateComposer.candidates(
            plan(BriefingPicker.SourceKind.CALENDAR, timeOfDay = "下午", topic = "会议"),
        )
        assertEquals("你今天下午有会议。", candidates.first())
        assertEquals("你今天有事。", candidates.last())
    }

    @Test
    fun everySourceKindHasAnUnconditionalFallback() {
        for (kind in BriefingPicker.SourceKind.entries) {
            assertTrue(TemplateComposer.candidates(plan(kind)).isNotEmpty())
        }
    }
}
