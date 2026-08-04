package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are transcribed from 256 card documents exported off the live account, field names and
 * markup included.
 */
class CardParserTest {

    private fun doc(template: String, title: String?, vararg fields: Pair<String, String>) =
        CardDoc.of("card-1", title, template, fields.toMap())

    @Test
    fun `reads an MSLK sentence through swapped field names`() {
        // The course has these two reversed on 130 of 160 sampled cards: `Pinyin` holds the
        // characters and `Chinese` holds the reading. Trusting the names gets the deck backwards.
        val parsed = CardParser.parse(
            "/Mandarin_Blueprint/MSLK Card",
            doc(
                "/Mandarin_Blueprint/MSLK Card",
                "You didn't eat anything, right？",
                "Chinese" to "Nǐ méiyǒu chī dōngxi, duì bu duì？",
                "Pinyin" to "你没有吃东西，对不对？",
                "English Translation" to "You didn't eat anything, right?",
                "Phrase #" to "0581",
            ),
        )

        assertEquals("你没有吃东西，对不对？", parsed.hanzi)
        assertEquals("Nǐ méi yǒu chī dōng xi duì bu duì", parsed.pinyin)
        assertEquals("You didn't eat anything, right?", parsed.english)
        assertTrue(parsed.isSentence)
    }

    @Test
    fun `reads the same card when the fields are the right way round`() {
        // The other 30 of 160. Whichever field holds Han characters is the Chinese.
        val parsed = CardParser.parse(
            "MSLK Card",
            doc(
                "MSLK Card",
                "Twenty-three",
                "Chinese" to "二十三",
                "Pinyin" to "èr shí sān",
                "English Translation" to "Twenty-three",
            ),
        )

        assertEquals("二十三", parsed.hanzi)
        assertEquals("èr shí sān", parsed.pinyin)
    }

    @Test
    fun `splits a reading grouped by word rather than by character`() {
        // Cards write `zhōuwǔ`, not `zhōu wǔ`. Counting tokens rejected 60% of the deck.
        val parsed = CardParser.parse(
            "MSLK Card",
            doc("MSLK Card", "It's the fourth", "Chinese" to "Zhèi ge zhōuwǔ shì sì hào.", "Pinyin" to "这个周五是四号。"),
        )

        assertEquals("Zhèi ge zhōu wǔ shì sì hào", parsed.pinyin)
    }

    @Test
    fun `keeps a sentence containing erhua aligned`() {
        // 这儿 is `zhèr` — two characters, one syllable. Before this, a single 儿 anywhere threw
        // away the reading for every word in the sentence, not just its own.
        val parsed = CardParser.parse(
            "MSLK Card",
            doc("MSLK Card", "Not here", "Chinese" to "Tā bú zài zhèr.", "Pinyin" to "他不在这儿。"),
        )

        // The contracted syllable is kept whole — `zhèr` is the reading of 这儿 — and the 儿 holds
        // a blank slot so every later character still lines up with its own syllable.
        assertEquals("Tā bú zài zhèr", parsed.pinyin)
    }

    @Test
    fun `reads a cloze card, whose blanks are the reading`() {
        val parsed = CardParser.parse(
            "/Mandarin_Blueprint/MB PM Cloze",
            doc(
                "/Mandarin_Blueprint/MB PM Cloze",
                "明天",
                "Characters" to "明天",
                "Pinyin" to "{{c1::míng}}{{c2::tiān}}",
                "English" to "tomorrow",
                "Picture" to "![](https://storage.googleapis.com/x.jpeg)",
            ),
        )

        assertEquals("明天", parsed.hanzi)
        assertEquals("míng tiān", parsed.pinyin)
        assertEquals("tomorrow", parsed.english)
        assertFalse(parsed.isSentence)
    }

    @Test
    fun `reads a dialogue cloze across both speakers`() {
        val parsed = CardParser.parse(
            "MB PM Cloze",
            doc(
                "MB PM Cloze",
                "A: 吃饭了吗？",
                "Characters" to "A: 吃饭了吗？\n\nB: 吃了。",
                "Pinyin" to "A: chī fàn le ma?\n\nB: chī le.",
                "English" to "Have you eaten?",
            ),
        )

        assertEquals("chī fàn le ma chī le", parsed.pinyin)
        assertTrue(parsed.isSentence)
    }

    @Test
    fun `reads a movie card through its HTML wrapping`() {
        val parsed = CardParser.parse(
            "/Mandarin_Blueprint/MOVIE REVIEW",
            doc(
                "/Mandarin_Blueprint/MOVIE REVIEW",
                "个",
                "HANZI" to "<p>个</p>",
                "PINYIN" to "<p>gè</p>",
                "KEYWORD" to "<p>Individual</p>",
                "NOTES" to "[Source Video Lesson](https://courses.mandarinblueprint.com/x)",
            ),
        )

        assertEquals("个", parsed.hanzi)
        assertEquals("gè", parsed.pinyin)
        assertEquals("Individual", parsed.english)
    }

    @Test
    fun `reads a word-connection card, which names all three`() {
        val parsed = CardParser.parse(
            "WORD CONNECTION REVIEW",
            doc(
                "WORD CONNECTION REVIEW",
                "一半",
                "WORD" to "一半",
                "PINYIN" to "yībàn",
                "MEANING" to "one half",
                "MNEMONIC" to "==Black Knight==",
            ),
        )

        assertEquals("一半", parsed.hanzi)
        assertEquals("yī bàn", parsed.pinyin)
        assertEquals("one half", parsed.english)
    }

    @Test
    fun `matches field names case-insensitively`() {
        // A few word-connection cards carry both `WORD` and `Word`.
        val parsed = CardParser.parse(
            "WORD CONNECTION REVIEW",
            doc("WORD CONNECTION REVIEW", "认识", "Word" to "认识", "Pinyin" to "rènshi"),
        )

        assertEquals("认识", parsed.hanzi)
        assertEquals("rèn shi", parsed.pinyin)
    }

    @Test
    fun `takes a prop's component and leaves its mnemonic alone`() {
        // `PROP` names the mnemonic object ("Toilet"), which is not a translation of anything.
        val parsed = CardParser.parse(
            "PROP REVIEW",
            doc(
                "PROP REVIEW",
                "十（PROP）",
                "COMPONENT" to "十 ![](https://firebasestorage.googleapis.com/x.png)",
                "PROP" to "Toilet",
            ),
        )

        assertEquals("十", parsed.hanzi)
        assertNull(parsed.english)
    }

    @Test
    fun `yields nothing when the field it needs is gone`() {
        // The point of addressing by name: a renamed field fails completely, and the parse-rate
        // guard turns that into a visible error rather than a quietly wrong reading.
        val parsed = CardParser.parse(
            "MB PM Cloze",
            doc("MB PM Cloze", "明天", "Hanzi" to "明天", "Reading" to "míng tiān"),
        )

        assertEquals(ParsedCard.EMPTY, parsed)
    }

    @Test
    fun `refuses a reading that does not fit the characters`() {
        // A cloze covering one syllable of four is not the word's reading.
        val parsed = CardParser.parse(
            "MB PM Cloze",
            doc("MB PM Cloze", "吃饭了吗", "Characters" to "吃饭了吗", "Pinyin" to "{{c1::chī}}"),
        )

        assertEquals("吃饭了吗", parsed.hanzi)
        assertNull(parsed.pinyin)
    }

    @Test
    fun `trusts the document's own template over the caller's`() {
        // A card with two prompts has two schedule rows; picking one to decide how to read the
        // card is arbitrary, and the document says so itself.
        val parsed = CardParser.parse(
            "/Mandarin_Blueprint/PROP REVIEW",
            doc("/Mandarin_Blueprint/MOVIE REVIEW", "人", "HANZI" to "人", "PINYIN" to "rén"),
        )

        assertEquals("rén", parsed.pinyin)
    }

    @Test
    fun `leaves unmapped templates to the generic scan`() {
        assertFalse(CardParser.handles("/Mandarin_Blueprint/ACTOR REVIEW"))
        assertFalse(CardParser.handles("/Mandarin_Blueprint/SET REVIEW"))
        assertTrue(CardParser.handles("MSLK Card"))
        assertTrue(CardParser.handles("MB PM Cloze"))
        assertTrue(CardParser.handles("/Mandarin_Blueprint/MOVIE REVIEW"))
        assertTrue(CardParser.handles("/Mandarin_Blueprint/PROP REVIEW"))
    }

    @Test
    fun `survives a card with no document at all`() {
        assertEquals(ParsedCard.EMPTY, CardParser.parse("MSLK Card", null))
        assertEquals(ParsedCard.EMPTY, CardParser.parse("MSLK Card", doc("MSLK Card", null)))
    }
}
