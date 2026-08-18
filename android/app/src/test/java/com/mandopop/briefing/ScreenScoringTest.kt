package com.mandopop.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenScoringTest {

    private val dictionary = setOf("今天", "会议", "我们", "什么")
    private val known = setOf("我们", "今天", "有", "会议", "的")

    private fun score(text: String) =
        ScreenScoring.score(
            text,
            isWord = { it in dictionary || it in known },
            isKnown = { it in known },
        )

    @Test
    fun mostlyEnglishScreensProduceNoScore() {
        assertNull(score("Settings · Battery · 电量 low"))
    }

    @Test
    fun allKnownChineseScoresHundred() {
        val text = "我们今天有会议。".repeat(4)
        assertEquals(100, score(text)?.percentKnown)
    }

    @Test
    fun unknownWordsLowerTheScore() {
        // Enough Han to clear the minimum, with a solid block of unknown segments.
        val text = "我们今天有会议。我们今天有会议。什么什么什么什么什么什么什么。"
        val result = score(text)!!
        check(result.percentKnown in 1..99) { "expected partial score, got ${result.percentKnown}" }
    }
}
