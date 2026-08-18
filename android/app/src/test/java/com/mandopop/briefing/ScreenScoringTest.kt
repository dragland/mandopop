package com.mandopop.briefing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenScoringTest {

    private val dictionary = setOf("今天", "会议", "我们", "什么")
    private val known = setOf("我们", "今天", "有", "会议", "的", "咖啡", "朋友")
    private val english = mapOf(
        "coffee" to "咖啡", "friend" to "朋友", "meeting" to "会议",
        "battery" to "电池", "settings" to "设置", "message" to "消息",
        "download" to "下载", "update" to "更新", "network" to "网络",
    )

    private fun readable(text: String) =
        ScreenScoring.readable(
            text,
            isWord = { it in dictionary || it in known },
            isKnown = { it in known },
        )

    private suspend fun sayable(text: String) =
        ScreenScoring.sayable(text, isKnown = { it in known }) { english[it] }

    @Test
    fun shortMixedScreensProduceNoReadableScore() {
        assertNull(readable("Settings · Battery · 电量 low"))
    }

    @Test
    fun allKnownChineseScoresHundred() {
        val text = "我们今天有会议。".repeat(4)
        assertEquals(100, readable(text)?.percentKnown)
    }

    @Test
    fun unknownWordsLowerTheReadableScore() {
        val text = "我们今天有会议。我们今天有会议。什么什么什么什么什么什么什么。"
        val result = readable(text)!!
        check(result.percentKnown in 1..99) { "expected partial score, got ${result.percentKnown}" }
    }

    @Test
    fun englishScreenScoresBySayableVocabulary() = runTest {
        // 9 mappable words, 3 of them (coffee/friend/meeting) known in Chinese.
        val text = "coffee friend meeting battery settings message download update network " +
            "coffee friend meeting"
        val score = sayable(text)!!
        assertEquals(ScreenScoring.Flavor.SAYABLE, score.flavor)
        assertEquals(33, score.percentKnown)
        assertEquals(9, score.totalWords)
    }

    @Test
    fun properNounSoupProducesNoSayableScore() = runTest {
        // Nothing maps through the dictionary — no denominator, no score.
        assertNull(sayable("Zyxwv Qwertson visited Blorptown with Xanflip and Vromqux today"))
    }

    @Test
    fun linesNameTheirFlavor() {
        val readable = ScreenScoring.Score(ScreenScoring.Flavor.READABLE, 82, 40)
        val sayable = ScreenScoring.Score(ScreenScoring.Flavor.SAYABLE, 40, 20)
        assertEquals("Screen ≈82% readable", ScreenScoring.line(readable))
        assertEquals("Screen ≈40% sayable in Chinese", ScreenScoring.line(sayable))
    }
}
