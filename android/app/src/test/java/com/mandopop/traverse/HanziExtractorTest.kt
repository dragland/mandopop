package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HanziExtractorTest {

    @Test
    fun `finds hanzi embedded in mixed text`() {
        val result = HanziExtractor.candidates("认识", listOf("to know / 认识 / rèn shi"))
        assertEquals("认识", result.first())
    }

    @Test
    fun `prefers the card title over words quoted in the body`() {
        // Mnemonic bodies name other characters constantly; the card's own title is the word it
        // actually teaches.
        val result = HanziExtractor.candidates("中午", listOf("built from 中 and 午", "see also 上午"))
        assertEquals("中午", result.first())
    }

    @Test
    fun `prefers multi-character words over bare characters within a source`() {
        val result = HanziExtractor.candidates(null, listOf("中 午 中午"))
        assertEquals("中午", result.first())
    }

    @Test
    fun `ignores runs too long to be a word`() {
        val sentence = "我今天早上去了学校然后回家吃饭了真的很累"
        assertTrue(HanziExtractor.candidates(null, listOf(sentence)).isEmpty())
    }

    @Test
    fun `returns nothing when there is no hanzi at all`() {
        // Pinyin drill cards carry no characters; they must resolve to no candidates rather than
        // to garbage.
        assertTrue(HanziExtractor.candidates("-an", listOf("ACTOR", "PINYIN INITIAL", "b-")).isEmpty())
    }

    @Test
    fun `strips surrounding markup and punctuation`() {
        val result = HanziExtractor.candidates(null, listOf("**一半**（half）"))
        assertEquals("一半", result.first())
    }

    @Test
    fun `deduplicates repeated candidates`() {
        val result = HanziExtractor.candidates("个", listOf("个", "个", "一个"))
        assertEquals(listOf("个", "一个"), result)
    }

    @Test
    fun `title wins over a longer word found only in the body`() {
        // Source beats length: a single-character title is still the card's own subject, whereas a
        // longer word in the body is usually just an example using it.
        val result = HanziExtractor.candidates("日", listOf("as in 日本 and 生日"))
        assertEquals("日", result.first())
    }

    @Test
    fun `handles null and blank inputs`() {
        assertTrue(HanziExtractor.candidates(null, emptyList()).isEmpty())
        assertTrue(HanziExtractor.candidates("", listOf("", "   ")).isEmpty())
    }
}
