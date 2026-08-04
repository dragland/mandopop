package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card fixtures are transcribed from documents logged off the live account, markup and stray
 * fields included. Fields are addressed by shape rather than by key, so the cases that matter are
 * the ones where two strings could plausibly be mistaken for each other.
 */
class CardParserTest {

    private val mslkTemplate = "/Mandarin_Blueprint/MSLK Card"
    private val clozeTemplate = "MB PM Cloze"
    private val movieTemplate = "/Mandarin_Blueprint/MOVIE REVIEW"

    private fun doc(title: String?, vararg strings: String) = CardDoc("card-1", title, strings.toList())

    @Test
    fun `reads an MSLK sentence, its reading and its translation`() {
        val parsed = CardParser.parse(
            mslkTemplate,
            doc(
                "You say she is OK.",
                "Mandarin_Blueprint",
                "/Mandarin_Blueprint/MSLK Card",
                "08mdgwxgn8a08sxebjvxcn1c",
                "你说她很好。",
                "  &nbsp;\n[https://storage.googleapis.com/x/0059.mp3](https://storage.googleapis.com/x/0059.mp3)",
                "[MSLK_Lesson_03](/Mandarin_Blueprint/MSLK_Lesson_03)",
                "Nǐ shuō tā hěn hǎo.",
                "You say she is OK.",
                "0059",
                "English",
            ),
        )

        assertEquals("你说她很好。", parsed.hanzi)
        assertEquals("Nǐ shuō tā hěn hǎo", parsed.pinyin)
        // The prompt is English, which is why the old title-first rule contributed nothing here.
        assertEquals("You say she is OK.", parsed.english)
        assertTrue(parsed.isSentence)
    }

    @Test
    fun `keeps digits inside a sentence instead of reducing it to its characters`() {
        val parsed = CardParser.parse(
            mslkTemplate,
            doc("1776 -or literally- 1776 year", "1776年", "[MSLK_Lesson_13](/Mandarin_Blueprint/MSLK_Lesson_13)"),
        )

        assertEquals("1776年", parsed.hanzi)
    }

    @Test
    fun `ignores hanzi that only appears inside a card reference`() {
        // Card bodies link to other cards by path, and some of those paths carry literal
        // characters. Reading one as the card's own vocabulary is the scraping failure that got
        // ACTOR and SET excluded in the first place.
        val parsed = CardParser.parse(
            mslkTemplate,
            doc("Twenty-three", "/Mandarin_Blueprint/人（HANZI）", "二十三", "èr shí sān"),
        )

        assertEquals("二十三", parsed.hanzi)
        assertEquals("èr shí sān", parsed.pinyin)
    }

    @Test
    fun `ignores hanzi inside a markdown-wrapped reference too`() {
        // The same references appear both bare and wrapped. A wrapped one carrying characters
        // would read as a second candidate, and a second candidate makes the card unreadable —
        // so this is not cosmetic: it decides whether the card indexes at all.
        val parsed = CardParser.parse(
            mslkTemplate,
            doc("Twenty-three", "[人（HANZI）](/Mandarin_Blueprint/人（HANZI）)", "二十三", "èr shí sān"),
        )

        assertEquals("二十三", parsed.hanzi)
    }

    @Test
    fun `treats one sentence written twice as one candidate`() {
        // The same sentence with and without its full stop is not two candidates.
        val parsed = CardParser.parse(mslkTemplate, doc("Hello", "你好。", "你好", "nǐ hǎo"))

        assertEquals("你好。", parsed.hanzi)
        assertEquals("nǐ hǎo", parsed.pinyin)
    }

    @Test
    fun `takes nothing when two strings could each be the sentence`() {
        val parsed = CardParser.parse(mslkTemplate, doc("Ambiguous", "你好", "再见"))

        assertNull(parsed.hanzi)
    }

    @Test
    fun `lines up a reading across erhua, where two characters share one syllable`() {
        // 哪儿 is nǎr. Counting characters against syllables would come up one short and throw the
        // whole sentence's reading away, not just this word's.
        val parsed = CardParser.parse(mslkTemplate, doc("Where is he?", "他在哪儿", "tā zài nǎr"))

        assertEquals("他在哪儿", parsed.hanzi)
        assertEquals("tā zài nǎr", parsed.pinyin)
    }

    @Test
    fun `still gives 儿 its own syllable when it has one`() {
        val parsed = CardParser.parse(mslkTemplate, doc("His son", "他儿子", "tā ér zi"))

        assertEquals("tā ér zi", parsed.pinyin)
    }

    @Test
    fun `counts the ideographic zero as a character`() {
        val parsed = CardParser.parse(mslkTemplate, doc("2026", "二〇二六", "èr líng èr liù"))

        assertEquals("èr líng èr liù", parsed.pinyin)
    }

    @Test
    fun `reads a cloze card's word from its title and its reading from the blank`() {
        val parsed = CardParser.parse(
            clozeTemplate,
            doc(
                "明天",
                "明天",
                "Mandarin_Blueprint",
                "/Mandarin_Blueprint/Pronunciation Mastery",
                "![](https://storage.googleapis.com/x/screenshot.jpeg)",
                "{{c1::míng}}{{c2::tiān}}",
                "tomorrow",
                "[Source Video Lesson](https://www.mandarinblueprint.courses/x)",
            ),
        )

        assertEquals("明天", parsed.hanzi)
        assertEquals("míng tiān", parsed.pinyin)
        assertFalse(parsed.isSentence)
    }

    @Test
    fun `keeps the card's own word when the title pairs it with an example`() {
        val parsed = CardParser.parse(clozeTemplate, doc("渴 她渴了", "{{c1::kě}}"))

        assertEquals("渴", parsed.hanzi)
        assertEquals("kě", parsed.pinyin)
    }

    @Test
    fun `refuses a reading that covers only part of the word`() {
        // Dialogue clozes blank one syllable out of a whole line. A reading that does not line up
        // character-for-character is a misread field, and a wrong reading is worse than none.
        val parsed = CardParser.parse(clozeTemplate, doc("A: 吃饭了吗？B: 吃了。", "A: {{c1::chī}}fàn le ma?"))

        assertEquals("吃饭了吗", parsed.hanzi)
        assertNull(parsed.pinyin)
    }

    @Test
    fun `refuses a cloze that blanks the characters rather than the reading`() {
        // The cloze path is the one that does not go through the pinyin test, and Han characters
        // count as letters — so a syllable count alone would happily store 吃 饭 as a reading.
        val parsed = CardParser.parse(clozeTemplate, doc("吃饭", "{{c1::吃}}{{c2::饭}}"))

        assertEquals("吃饭", parsed.hanzi)
        assertNull(parsed.pinyin)
    }

    @Test
    fun `refuses a cloze that blanks the English`() {
        assertNull(CardParser.parse(clozeTemplate, doc("明", "{{c1::bright}}")).pinyin)
    }

    @Test
    fun `reads a movie card through its HTML wrapping`() {
        val parsed = CardParser.parse(
            movieTemplate,
            doc(
                "中",
                "<p>Middle/Centre</p>",
                "<p><a href=\"https://courses.mandarinblueprint.com/x\">Source Video Lesson</a></p>",
                "<p><strong>Prop(s): </strong><react-embed src=\"https://traverse.link/x\"></react-embed></p>",
                "<p>zhōng</p>",
                "<p><img src=\"https://firebasestorage.googleapis.com/x.gif\"></p><p></p>",
                "<p>中</p>",
            ),
        )

        assertEquals("中", parsed.hanzi)
        assertEquals("zhōng", parsed.pinyin)
    }

    @Test
    fun `takes no reading when a mnemonic quotes one too`() {
        // The pinyin test is deliberately loose — a line of English that happens to quote a
        // tone-marked syllable counts as a candidate. That errs the safe way: a second candidate
        // means neither is taken, so a lookalike costs a reading rather than inventing one.
        val parsed = CardParser.parse(movieTemplate, doc("人", "<p>rén</p>", "<p>as in the rén of rén shi</p>"))

        assertEquals("人", parsed.hanzi)
        assertNull(parsed.pinyin)
    }

    @Test
    fun `leaves prop and word-connection cards to the generic scan`() {
        assertFalse(CardParser.handles("/Mandarin_Blueprint/PROP REVIEW"))
        assertFalse(CardParser.handles("/Mandarin_Blueprint/WORD CONNECTION REVIEW"))
        assertEquals(ParsedCard.EMPTY, CardParser.parse("/Mandarin_Blueprint/PROP REVIEW", doc("一", "一")))
    }

    @Test
    fun `claims the templates it knows however they are qualified`() {
        assertTrue(CardParser.handles("MSLK Card"))
        assertTrue(CardParser.handles("/Mandarin_Blueprint/MSLK Card"))
        assertTrue(CardParser.handles("MB PM Cloze"))
        assertTrue(CardParser.handles("/Mandarin_Blueprint/MOVIE REVIEW"))
    }

    @Test
    fun `survives a card with no document at all`() {
        assertEquals(ParsedCard.EMPTY, CardParser.parse(mslkTemplate, null))
        assertEquals(ParsedCard.EMPTY, CardParser.parse(clozeTemplate, doc(null)))
    }
}
