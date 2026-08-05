package com.mandopop.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are transcribed from the course's own cards — all 55,460 of them were exported and the
 * layouts below verified against every one, so these cover shapes this account has not yet reached.
 */
class CardParserTest {

    private fun doc(template: String, title: String?, vararg fields: Pair<String, String>) =
        CardDoc.of("card-1", title, template, fields.toMap())

    private companion object {
        /** Recomputed by the failure message when extraction legitimately changes. */
        const val EXPECTED_EXTRACTION = -789297038
    }

    @Test
    fun `reads an MSLK sentence through swapped field names`() {
        // The course has these two reversed on 130 of 160 sampled cards: `Pinyin` holds the
        // characters and `Chinese` holds the reading. Trusting the names gets the deck backwards.
        val parsed = CardParser.parse(doc(
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
    }

    @Test
    fun `reads the same card when the fields are the right way round`() {
        // The other 30 of 160. Whichever field holds Han characters is the Chinese.
        val parsed = CardParser.parse(doc(
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
        val parsed = CardParser.parse(doc("MSLK Card", "It's the fourth", "Chinese" to "Zhèi ge zhōuwǔ shì sì hào.", "Pinyin" to "这个周五是四号。"),
        )

        assertEquals("Zhèi ge zhōu wǔ shì sì hào", parsed.pinyin)
    }

    @Test
    fun `keeps a sentence containing erhua aligned`() {
        // 这儿 is `zhèr` — two characters, one syllable. Before this, a single 儿 anywhere threw
        // away the reading for every word in the sentence, not just its own.
        val parsed = CardParser.parse(doc("MSLK Card", "Not here", "Chinese" to "Tā bú zài zhèr.", "Pinyin" to "他不在这儿。"),
        )

        // The contracted syllable is kept whole — `zhèr` is the reading of 这儿 — and the 儿 holds
        // a blank slot so every later character still lines up with its own syllable.
        assertEquals("Tā bú zài zhèr", parsed.pinyin)
    }

    @Test
    fun `reads a cloze card, whose blanks are the reading`() {
        val parsed = CardParser.parse(doc(
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
    }

    @Test
    fun `reads a dialogue cloze across both speakers`() {
        val parsed = CardParser.parse(doc(
                "MB PM Cloze",
                "A: 吃饭了吗？",
                "Characters" to "A: 吃饭了吗？\n\nB: 吃了。",
                "Pinyin" to "A: chī fàn le ma?\n\nB: chī le.",
                "English" to "Have you eaten?",
            ),
        )

        assertEquals("chī fàn le ma chī le", parsed.pinyin)
    }

    @Test
    fun `reads a movie card through its HTML wrapping`() {
        val parsed = CardParser.parse(doc(
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
        val parsed = CardParser.parse(doc(
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
        val parsed = CardParser.parse(doc("WORD CONNECTION REVIEW", "认识", "Word" to "认识", "Pinyin" to "rènshi"),
        )

        assertEquals("认识", parsed.hanzi)
        assertEquals("rèn shi", parsed.pinyin)
    }

    @Test
    fun `takes a prop's component and leaves its mnemonic alone`() {
        // `PROP` names the mnemonic object ("Toilet"), which is not a translation of anything.
        val parsed = CardParser.parse(doc(
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
        // The point of addressing by name: a renamed field fails completely for that template, and
        // the parse-rate guard turns it into a visible error rather than a quietly wrong reading.
        // (`Hanzi` would still work — lookup is case-insensitive, so it matches MOVIE's `HANZI`.)
        val parsed = CardParser.parse(doc("MB PM Cloze", "明天", "Zi" to "明天", "Duyin" to "míng tiān"))

        assertEquals(ParsedCard.EMPTY, parsed)
    }

    @Test
    fun `refuses a reading that does not fit the characters`() {
        // A cloze covering one syllable of four is not the word's reading.
        val parsed = CardParser.parse(doc("MB PM Cloze", "吃饭了吗", "Characters" to "吃饭了吗", "Pinyin" to "{{c1::chī}}"),
        )

        assertEquals("吃饭了吗", parsed.hanzi)
        assertNull(parsed.pinyin)
    }

    @Test
    fun `ignores the template name entirely`() {
        // Not even consulted: a card with two prompts has two schedule rows naming it differently,
        // and three of the course's templates are named by meaningless slug.
        assertEquals("rén", CardParser.parse(
            doc("utter nonsense", "人", "HANZI" to "人", "PINYIN" to "rén"),
        ).pinyin)
    }

    @Test
    fun `reads templates it has never been told about`() {
        // Matched on fields, not names — which is why three templates named only by slug, and the
        // 30,000 Language Islands and TPV cards nobody had mapped, read without being enumerated.
        assertEquals("已", CardParser.parse(
            doc("r49a6yz1hfeydz7ocl9w7jua", "已", "HANZI" to "<p>已</p>", "PINYIN" to "<p>yǐ</p>"),
        ).hanzi)
        assertEquals("我不想练健美。", CardParser.parse(
            doc("Language Islands - Production", "I don't want to bodybuild.",
                "Chinese" to "我不想练健美。", "Pinyin" to "Wǒ bùxiǎng liàn jiànměi.",
                "English Translation" to "I don't want to bodybuild."),
        ).hanzi)
        assertEquals("祭奠", CardParser.parse(
            doc("MB Sentence", "清明节是中国人祭奠先人的节日。",
                "Sentence" to "清明节是中国人==祭奠==先人的节日。", "Word" to "祭奠 1",
                "Usage Definition" to "用法 1 - v. to offer sacrifices"),
        ).hanzi)
        assertEquals("鼻子", CardParser.parse(
            doc("MB Basic Card-a2abe", "鼻子", "Word" to "鼻子", "Pinyin" to "==bízi==",
                "English" to "Nose"),
        ).hanzi)
    }

    @Test
    fun `has no layout for a card that teaches only a sound`() {
        // ACTOR, SET and Minimal Pairs answer false without being named, because none of them
        // carries a field on the list.
        assertFalse(CardParser.handles(doc("ACTOR REVIEW", "-an", "ACTOR" to "b-", "PINYIN INITIAL" to "b")))
        assertFalse(CardParser.handles(doc("Minimal Pairs", "ji2", "Word 1" to "ji2", "Word 2" to "qi2")))
        assertTrue(CardParser.handles(doc("MSLK Card", "x", "Chinese" to "你好", "Pinyin" to "nǐ hǎo")))
    }

    @Test
    fun `extraction is pinned to the parser version`() {
        // The one rule nothing else enforces: a change to what gets read off a card only reaches
        // the 938 already-cached rows if CardParser.VERSION moves. Forgetting leaves correct code
        // serving stale output, with every test still green — so changing extraction has to break
        // this, and fixing it means touching the version.
        val extraction = listOf(
            CardParser.parse(doc("MSLK Card", "You say she is OK.", "Chinese" to "Nǐ shuō tā hěn hǎo.", "Pinyin" to "你说她很好。", "English Translation" to "You say she is OK.")),
            CardParser.parse(doc("MB PM Cloze", "明天", "Characters" to "明天", "Pinyin" to "{{c1::míng}}{{c2::tiān}}", "English" to "tomorrow")),
            CardParser.parse(doc("MOVIE REVIEW", "个", "HANZI" to "<p>个</p>", "PINYIN" to "<p>gè</p>", "KEYWORD" to "<p>Individual</p>")),
            CardParser.parse(doc("WORD CONNECTION REVIEW", "一半", "WORD" to "一半", "PINYIN" to "yībàn", "MEANING" to "one half")),
            CardParser.parse(doc("PROP REVIEW", "十（PROP）", "COMPONENT" to "十 ![](https://x.png)", "PROP" to "Toilet")),
            // Exercises the syllable splitter, not just field addressing — the `ue` gap and the
            // false-contraction bug both changed readings without touching the fixtures above.
            CardParser.parse(doc("MSLK Card", "Twelve months", "Chinese" to "Shíèr yuè xué xí", "Pinyin" to "十二月学习")),
        ).joinToString("|")

        assertEquals(
            "extraction changed — bump CardParser.VERSION so the cached deck is re-read, " +
                "then update this fingerprint",
            EXPECTED_EXTRACTION,
            extraction.hashCode(),
        )
        assertEquals(8, CardParser.VERSION)
    }

    @Test
    fun `survives a card with no document at all`() {
        assertEquals(ParsedCard.EMPTY, CardParser.parse(null))
        assertEquals(ParsedCard.EMPTY, CardParser.parse(doc("MSLK Card", null)))
    }
}
