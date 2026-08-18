package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClozePickerTest {

    private val dictionary = setOf("今天", "会议", "开会", "我们", "重要", "什么", "时候")
    private val known =
        setOf("我", "们", "我们", "今天", "有", "会议", "开", "会", "开会", "一", "个", "重要", "的")

    private fun pick(sentences: List<String>, word: String, seed: Int = 0) =
        ClozePicker.pick(
            sentences,
            word,
            isWord = { it in dictionary || it in known },
            isKnown = { it in known },
            seed = seed,
        )

    @Test
    fun picksSentenceWhereEveryWordIsKnown() {
        assertEquals(
            "我们今天有一个重要的会议。",
            pick(listOf("我们今天有一个重要的会议。"), "会议"),
        )
    }

    @Test
    fun rejectsSentenceWithAnUnknownWord() {
        // 什么时候 contains words outside the known set — showing it would smuggle vocabulary
        // into a recall prompt.
        assertNull(pick(listOf("会议什么时候开？"), "会议"))
    }

    @Test
    fun rejectsSentencesNotContainingTheWord() {
        assertNull(pick(listOf("我们今天开会。"), "会议"))
    }

    @Test
    fun rejectsOverlongSentences() {
        val long = "我们今天有一个重要的会议。".repeat(3)
        assertNull(pick(listOf(long), "会议"))
    }

    @Test
    fun rotationIsStableWithinADayAndMovesAcrossDays() {
        val sentences = listOf("我们今天有会议。", "今天的会议重要。")
        val today = ClozePicker.seed(20_000, "会议")
        val tomorrow = ClozePicker.seed(20_001, "会议")
        assertEquals(pick(sentences, "会议", today), pick(sentences, "会议", today))
        // Not guaranteed different for every pair, but for two candidates adjacent seeds differ.
        assertNotEquals(pick(sentences, "会议", today), pick(sentences, "会议", tomorrow))
    }

    @Test
    fun negativeSeedStillPicks() {
        assertEquals(
            "我们今天有会议。",
            pick(listOf("我们今天有会议。"), "会议", seed = Int.MIN_VALUE + 1),
        )
    }
}
