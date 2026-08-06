package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases taken from real cards; each one changed what got stored. */
class ChineseTextTest {

    @Test
    fun `collapses spaces the JVM does not consider whitespace`() {
        // Android's regex engine is ICU and matches these; the JVM's `\s` does not. With `\s` the
        // unit tests and the device disagreed about what the same card said.
        assertEquals("妈 爸", ChineseText.stripMarkup("妈 爸"))
        assertEquals("你 好", ChineseText.stripMarkup("你　好"))
    }

    @Test
    fun `resolves escaped markdown punctuation`() {
        assertEquals("我叫... -OR- 我是...", ChineseText.stripMarkup("我叫...\n\n\\-OR-\n\n我是..."))
    }

    @Test
    fun `drops a deck-authoring disambiguator so the word can be looked up`() {
        assertEquals("在", ChineseText.trimPunctuation("在（1）"))
        assertEquals("谢谢", ChineseText.trimPunctuation("谢谢！"))
        assertEquals("为什么", ChineseText.trimPunctuation("为什么？"))
    }

    @Test
    fun `drops the marks highlighting a sentence's target word`() {
        assertEquals("清明节是中国人祭奠先人的节日。", ChineseText.stripMarkup("清明节是中国人==祭奠==先人的节日。"))
        assertEquals("bízi", ChineseText.stripMarkup("==bízi=="))
    }

    @Test
    fun `keeps digits, which are not punctuation`() {
        assertEquals("1776年", ChineseText.trimPunctuation("1776年"))
    }
}
