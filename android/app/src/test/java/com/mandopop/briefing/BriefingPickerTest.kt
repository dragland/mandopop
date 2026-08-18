package com.mandopop.briefing

import com.mandopop.data.FrontierWord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BriefingPickerTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val known = setOf("今天", "下午", "上午", "晚上", "会议", "朋友", "咖啡")
    private val frontier = listOf(
        FrontierWord("医生", "yīshēng", "doctor"),
        FrontierWord("机场", "jīchǎng", "airport"),
    )
    private val dictionary = mapOf(
        "meeting" to "会议",
        "coffee" to "咖啡",
        "friend" to "朋友",
        "doctor" to "医生",
        "dentist" to "牙医",
    )

    private fun at(hour: Int): Long =
        ZonedDateTime.now(zone).withHour(hour).withMinute(0).toInstant().toEpochMilli()

    private fun inputs(
        events: List<CalendarEvent> = emptyList(),
        notifications: List<ActiveNotification> = emptyList(),
        screen: ScreenSnapshot? = null,
    ) = BriefingInputs(System.currentTimeMillis(), events, notifications, screen)

    private suspend fun plan(inputs: BriefingInputs) =
        BriefingPicker.plan(inputs, known, frontier, zone) { dictionary[it] }

    @Test
    fun calendarEventBeatsNotifications() = runTest {
        val plan = plan(
            inputs(
                events = listOf(CalendarEvent("Team meeting", at(15), at(16), allDay = false)),
                notifications = listOf(
                    ActiveNotification("Chat", "Coffee?", "coffee at 4", "msg", 1L),
                ),
            ),
        )!!
        assertEquals(BriefingPicker.SourceKind.CALENDAR, plan.kind)
        assertTrue(plan.words.contains("会议"))
        assertEquals("下午", plan.timeOfDay)
        assertEquals("会议", plan.topic)
    }

    @Test
    fun calendarIsBriefableWithoutMappableTitle() = runTest {
        val plan = plan(
            inputs(events = listOf(CalendarEvent("Sprint retro", at(9), at(10), allDay = false))),
        )!!
        assertEquals(BriefingPicker.SourceKind.CALENDAR, plan.kind)
        assertEquals("上午", plan.timeOfDay)
        assertNull(plan.topic)
    }

    @Test
    fun frontierWordFillsTheIntroductionSlot() = runTest {
        val plan = plan(
            inputs(events = listOf(CalendarEvent("Doctor appointment", at(19), at(20), allDay = false))),
        )!!
        assertEquals("医生", plan.frontier?.hanzi)
        assertTrue(plan.words.contains("医生"))
        assertEquals("晚上", plan.timeOfDay)
    }

    @Test
    fun personalNotificationOutranksNewerPromo() = runTest {
        val plan = plan(
            inputs(
                notifications = listOf(
                    ActiveNotification("Shop", "Sale on coffee", "50% off", "promo", 2L),
                    ActiveNotification("Messages", "friend: coffee later?", "", "msg", 1L),
                ),
            ),
        )!!
        assertEquals(BriefingPicker.SourceKind.NOTIFICATION, plan.kind)
        assertTrue(plan.gist.contains("Messages"))
    }

    @Test
    fun unmappableNotificationFallsThroughToScreen() = runTest {
        val plan = plan(
            inputs(
                notifications = listOf(
                    ActiveNotification("System", "Qzx qzx", "qzx", null, 1L),
                ),
                screen = ScreenSnapshot("com.browser", "an article about coffee and coffee farming", 0L),
            ),
        )!!
        assertEquals(BriefingPicker.SourceKind.SCREEN, plan.kind)
        assertEquals("咖啡", plan.topic)
    }

    @Test
    fun expressibleNotificationBeatsUnmappableCalendarEvent() = runTest {
        // A first calendar event whose title maps to nothing must not eat the briefing while
        // a notification full of known vocabulary waits behind it.
        val plan = plan(
            inputs(
                events = listOf(CalendarEvent("Alexander Bobrov PTO", at(9), at(10), allDay = true)),
                notifications = listOf(
                    ActiveNotification("Messages", "coffee with a friend?", "", "msg", 1L),
                ),
            ),
        )!!
        assertEquals(BriefingPicker.SourceKind.NOTIFICATION, plan.kind)
        assertEquals("咖啡", plan.topic)
    }

    @Test
    fun secondCalendarEventWinsWhenFirstIsUnmappable() = runTest {
        val plan = plan(
            inputs(
                events = listOf(
                    CalendarEvent("Alexander Bobrov PTO", at(9), at(10), allDay = true),
                    CalendarEvent("Team meeting", at(15), at(16), allDay = false),
                ),
            ),
        )!!
        assertEquals(BriefingPicker.SourceKind.CALENDAR, plan.kind)
        assertEquals("会议", plan.topic)
    }

    @Test
    fun nothingRelevantMeansNoPlan() = runTest {
        assertNull(plan(inputs(screen = ScreenSnapshot("com.app", "qzx qzx qzx", 0L))))
        assertNull(plan(inputs()))
    }

    @Test
    fun contentWordsDropStopwordsAndRankByFrequency() {
        val words = BriefingPicker.contentWords(
            "The coffee was great and the coffee shop had great coffee for you",
        )
        assertEquals("coffee", words.first())
        assertTrue("the" !in words)
        assertTrue("you" !in words)
    }
}
