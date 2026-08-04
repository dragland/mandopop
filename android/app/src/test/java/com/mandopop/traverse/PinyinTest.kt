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
