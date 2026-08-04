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
    fun `case is the whole signal, so matching without it picks the surname`() {
        // 花's surname and its common sense share a spelling and differ only in capitalisation.
        // A case-insensitive match finds `Huā` first and is therefore no better than no match at
        // all — which is exactly what the first version of this did.
        assertEquals("surname Hua", hua.first { it.pinyin.equals("huā", true) }.definitions.first())
        assertEquals("flower", KnownWordIndex.preferredEntry(hua, "huā")?.definitions?.first())
    }

    @Test
    fun `a capitalised reading still resolves to the surname when that is what the card said`() {
        assertEquals("surname Hua", KnownWordIndex.preferredEntry(hua, "Huā")?.definitions?.first())
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
    fun `falls back to CC-CEDICT's own order when the card gave no reading`() {
        assertEquals("surname Hua", KnownWordIndex.preferredEntry(hua, null)?.definitions?.first())
        assertNull(KnownWordIndex.preferredEntry(emptyList(), "huā"))
    }

    @Test
    fun `an unmatched reading does not discard the word`() {
        // The reading came off the card and the dictionary disagrees; the word is still known, so
        // something has to be returned rather than nothing.
        assertEquals("surname Hua", KnownWordIndex.preferredEntry(hua, "zzz")?.definitions?.first())
    }
}
