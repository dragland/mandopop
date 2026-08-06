package com.mandopop.traverse

import com.mandopop.dictionary.CedictEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Entry rows are transcribed from the committed `cedict.json`, in the order reverse lookup returns
 * them — which is row id order, which is why the surnames come first.
 */
class KnownWordIndexTest {

    private fun entry(pinyin: String, vararg definitions: String) =
        CedictEntry(simplified = "x", pinyin = pinyin, definitions = definitions.toList())

    private val hua = listOf(
        entry("Huā", "surname Hua"),
        entry("huā", "flower"),
        entry("huā", "old variant of 花"),
    )

    @Test
    fun `a card's reading beats CC-CEDICT's surname-first ordering`() {
        assertEquals("flower", KnownWordIndex.preferredEntry(hua, "huā")?.definitions?.first())
    }

    @Test
    fun `case cannot decide it, because a card capitalises whatever opens a sentence`() {
        // `Huā` is how the card writes 花 at the head of a sentence, and it matches CC-CEDICT's
        // surname row exactly. So case is not the signal — CC-CEDICT's own "surname" label is.
        assertEquals("flower", KnownWordIndex.preferredEntry(hua, "Huā")?.definitions?.first())
        assertEquals("flower", KnownWordIndex.preferredEntry(hua, "huā")?.definitions?.first())
    }

    @Test
    fun `skips a cross-reference row for a real definition at the same reading`() {
        // 和 leads with a pointer to itself, at the very reading the deck uses. It is the most
        // common word in the deck and was glossed "old variant of 和" on 45 cards.
        val he = listOf(
            entry("hé", "old variant of 和[he2]"),
            entry("Hé", "surname He"),
            entry("hé", "(joining two nouns) and; together with"),
        )

        assertEquals(
            "(joining two nouns) and; together with",
            KnownWordIndex.preferredEntry(he, "hé")?.definitions?.first(),
        )
    }

    @Test
    fun `keeps a capitalised entry when nothing marks it a name`() {
        // 周日 is `Zhōu rì` "Sunday" and `zhōu rì` "(dialect) weekday". Neither is a surname, so
        // the exact spelling the card gave decides — and lowercasing it first chose the dialect.
        val sunday = listOf(entry("Zhōu rì", "Sunday"), entry("zhōu rì", "(dialect) weekday"))

        assertEquals("Sunday", KnownWordIndex.preferredEntry(sunday, "Zhōu rì")?.definitions?.first())
    }

    @Test
    fun `refuses a word that is only a writing component`() {
        val radical = listOf(entry("rén", "\"person\" radical in Chinese characters (Kangxi radical 9)"))

        assertNull(KnownWordIndex.preferredEntry(radical, "rén"))
    }

    @Test
    fun `tone distinguishes senses when spelling alone does not`() {
        val guo = listOf(
            entry("Guō", "surname Guo"),
            entry("guò", "to cross"),
            entry("guo", "experienced action marker"),
        )
        assertEquals("to cross", KnownWordIndex.preferredEntry(guo, "guò")?.definitions?.first())
        assertEquals(
            "experienced action marker",
            KnownWordIndex.preferredEntry(guo, "guo")?.definitions?.first(),
        )
    }

    @Test
    fun `spacing in a reading is not a difference`() {
        val entries = listOf(entry("Mǎ", "surname Ma"), entry("dōng xi", "thing"))
        assertEquals("thing", KnownWordIndex.preferredEntry(entries, "dōngxi")?.definitions?.first())
    }

    @Test
    fun `prefers a real meaning even with no reading to go on`() {
        assertEquals("flower", KnownWordIndex.preferredEntry(hua, null)?.definitions?.first())
        assertNull(KnownWordIndex.preferredEntry(emptyList(), "huā"))
    }

    @Test
    fun `an unmatched reading does not discard the word, or reinstate the surname`() {
        // A card typo (`tā yé hěn màn` for `yě`) matches no entry. Falling straight to the first
        // row put back the surname bias the whole function exists to remove.
        assertEquals("flower", KnownWordIndex.preferredEntry(hua, "zzz")?.definitions?.first())
    }
}
