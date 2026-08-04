package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Membership is passed in rather than read from CC-CEDICT, so these cover the cutting rule itself
 * and stay honest when the dictionary is rebuilt.
 */
class SegmenterTest {

    private fun words(vararg entries: String): (String) -> Boolean = entries.toSet()::contains

    private fun textOf(text: String, isWord: (String) -> Boolean) =
        Segmenter.segment(text, isWord).map { it.text }

    @Test
    fun `takes the longest dictionary word at each position`() {
        assertEquals(listOf("请", "吃", "东西"), textOf("请吃东西", words("东西")))
        assertEquals(listOf("他", "听不懂"), textOf("他听不懂", words("听不懂", "听不")))
    }

    @Test
    fun `emits single characters the dictionary does not know`() {
        // A lone component still came off a card the user studied; dropping it would silently
        // shorten the sentence.
        assertEquals(listOf("他", "很", "快", "吗"), textOf("他很快吗", words()))
    }

    @Test
    fun `puts a boundary in the wrong place when a real word straddles two`() {
        // Documented, not fixed, and taken from this deck: 二十个人 is "twenty people", but 个人
        // "individual, personal" is a genuine entry sitting across the boundary. Every output is
        // still a substring of the input, so the damage is a misplaced boundary, never a character
        // the user never saw.
        assertEquals(listOf("二十", "个人"), textOf("二十个人", words("二十", "个人")))
    }

    @Test
    fun `counts han characters only, so digits and punctuation do not shift the reading`() {
        val segments = Segmenter.segment("他1776年。", words())

        assertEquals(listOf("他", "年"), segments.map { it.text })
        assertEquals(listOf(0, 1), segments.map { it.hanIndex })
    }

    @Test
    fun `gives each word the syllables sitting over its own characters`() {
        val segments = Segmenter.segment("请吃东西", words("东西"))
        val syllables = listOf("qǐng", "chī", "dōng", "xi")

        assertEquals(
            listOf("qǐng", "chī", "dōng xi"),
            segments.map { Segmenter.readingFor(it, syllables) },
        )
    }

    @Test
    fun `offers no reading when the card had none`() {
        val segment = Segmenter.segment("东西", words("东西")).single()

        assertNull(Segmenter.readingFor(segment, null))
        // Short of the character count means the reading was misidentified upstream; slicing it
        // anyway would hand every later word somebody else's syllables.
        assertNull(Segmenter.readingFor(segment, listOf("dōng")))
    }

    @Test
    fun `collects every substring worth testing, and nothing longer than a word`() {
        assertEquals(setOf("东西", "西南", "东西南"), Segmenter.candidates("东西南"))
        assertEquals(emptySet<String>(), Segmenter.candidates("hello 123"))
    }

    @Test
    fun `handles empty and hanzi-free input`() {
        assertEquals(emptyList<Segment>(), Segmenter.segment("", words()))
        assertEquals(emptyList<Segment>(), Segmenter.segment("MSLK_Lesson_03", words()))
    }
}
