package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Readings are transcribed from real cards. The count check here is also the check that the right
 * field was read at all, so a case that ought to fail failing is as load-bearing as one passing.
 */
class PinyinTest {

    private fun align(hanzi: String, reading: String) = Pinyin.align(hanzi, reading)

    @Test
    fun `splits a reading grouped by word`() {
        assertEquals(
            listOf("Nǐ", "méi", "yǒu", "chī", "dōng", "xi", "duì", "bu", "duì"),
            align("你没有吃东西，对不对？", "Nǐ méiyǒu chī dōngxi, duì bu duì？"),
        )
    }

    @Test
    fun `already one token per character is left alone`() {
        assertEquals(listOf("èr", "shí", "sān"), align("二十三", "èr shí sān"))
    }

    @Test
    fun `keeps a whole group together when it is one syllable`() {
        assertEquals(listOf("yī", "bàn"), align("一半", "yībàn"))
        assertEquals(listOf("gè"), align("个", "gè"))
    }

    @Test
    fun `gives a contracted erhua syllable both its characters`() {
        // 这儿 is `zhèr`: one syllable, two characters. The 儿 takes a blank slot so anything after
        // it still lines up.
        assertEquals(listOf("Tā", "bú", "zài", "zhèr", ""), align("他不在这儿。", "Tā bú zài zhèr."))
    }

    @Test
    fun `does not let a would-be contraction pass as one syllable`() {
        // `shier` is spellable as `shie` + r, so it looked like a single contracted syllable — but
        // 二 is not 儿, so it never contracts. It read 十二 as one `shíèr` and then took the missing
        // syllable back by splitting `màn` into `mà` + `n` at the end of the sentence.
        assertEquals(
            listOf("Nà", "shí", "èr", "ge", "rén", "dōu", "hěn", "màn"),
            align("那十二个人都很慢。", "Nà shíèr ge rén dōu hěn màn"),
        )
        assertEquals(
            listOf("Shí", "èr", "yuè", "sān", "shí", "hào"),
            align("十二月三十号", "Shíèr yuè sān shí hào"),
        )
    }

    @Test
    fun `backtracks past a cut that only looks right`() {
        // All three of these have a longest-first decomposition that is wrong. `zhour|i` and
        // `shier|…` were rejected downstream and the card lost its reading; `sāng|è` was silently
        // accepted, because both halves happen to be spellable syllables.
        assertEquals(listOf("Zhōu", "rì"), align("周日", "Zhōurì"))
        assertEquals(
            listOf("Zhèi", "ge", "zhōu", "rì", "shì", "liù", "hào"),
            align("这个周日是六号。", "Zhèi ge zhōurì shì liù hào."),
        )
        assertEquals(listOf("Shí", "èr", "yuè"), align("十二月", "Shíèryuè"))
        assertEquals(
            listOf("Zhè", "sān", "gè", "rén", "dōu", "hěn", "kuài"),
            align("这三个人都很快。", "Zhè sāngè rén dōu hěn kuài."),
        )
    }

    @Test
    fun `still gives 儿 a syllable of its own when it has one`() {
        assertEquals(listOf("ér", "zi"), align("儿子", "ér zi"))
    }

    @Test
    fun `handles a syllable the frequency-filtered inventory would have missed`() {
        // `zhèi` is a colloquial reading of 这 and appears three times in all of CC-CEDICT. Building
        // the inventory from initials × finals keeps it; filtering by frequency did not.
        assertEquals(
            listOf("Zhèi", "ge", "zhōu", "wǔ", "shì", "sì", "hào"),
            align("这个周五是四号。", "Zhèi ge zhōuwǔ shì sì hào."),
        )
    }

    @Test
    fun `strips speaker markers from a dialogue`() {
        assertEquals(
            listOf("chī", "fàn", "le", "ma", "chī", "le"),
            align("A: 吃饭了吗？ B: 吃了。", "A: chī fàn le ma? B: chī le."),
        )
    }

    @Test
    fun `keeps an erhua syllable blanked across two cloze markers`() {
        // 玩儿 is clozed as two blanks, `wán` and `r`. Separating them left a bare `r`, which is
        // not a syllable, so the card lost its reading entirely.
        assertEquals(listOf("wánr", ""), align("玩儿", "{{c1::wán}}{{c2::r}}"))
    }

    @Test
    fun `reads across a card that offers two phrasings`() {
        assertEquals(
            listOf("hàn", "zì", "zì"),
            align("汉字 or 字", "{{c2::hàn}}{{c1::zì}} or {{c1::zì}}"),
        )
        assertEquals(
            listOf("wǒ", "jiào", "wǒ", "shì"),
            align("我叫 -OR- 我是", "wǒ {{c1::jiào}} -OR- wǒ {{c2::shì}}"),
        )
    }

    @Test
    fun `strips a multi-letter label, not just a speaker initial`() {
        assertEquals(
            listOf("nǎ", "nà", "zhè"),
            align("Which: 哪 That: 那 This: 这", "Which: {{c1::nǎ}} That: {{c2::nà}} This: {{c3::zhè}}"),
        )
    }

    @Test
    fun `takes the first of two offered readings`() {
        assertEquals(listOf("Nà"), align("那", "Nà/Nèi"))
    }

    @Test
    fun `reads cloze blanks as the reading`() {
        assertEquals(listOf("míng", "tiān"), align("明天", "{{c1::míng}}{{c2::tiān}}"))
    }

    @Test
    fun `counts the ideographic zero as a character`() {
        assertEquals(listOf("èr", "líng", "èr", "liù"), align("二〇二六", "èr líng èr liù"))
    }

    @Test
    fun `refuses a reading that is short`() {
        // A cloze covering one syllable of four. No reading beats a wrong one.
        assertNull(align("吃饭了吗", "chī"))
    }

    @Test
    fun `refuses a reading that is long`() {
        assertNull(align("明天", "míng tiān hòu tiān"))
    }

    @Test
    fun `refuses something that is not a reading at all`() {
        // This is the case that matters: it is what stops a misidentified field being stored, and
        // Han characters are letters as far as tokenising is concerned.
        assertNull(align("吃饭", "吃 饭"))
        assertNull(align("明天", "tomorrow"))
        assertNull(align("明天", "front-0"))
    }

    @Test
    fun `handles empty input`() {
        assertNull(align("明天", ""))
        assertNull(align("", "míng tiān"))
        assertNull(Pinyin.align("明天", null))
    }
}
