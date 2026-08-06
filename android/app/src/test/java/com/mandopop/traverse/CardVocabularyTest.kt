package com.mandopop.traverse

import com.mandopop.traverse.CardVocabulary.Companion.brokenTemplate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that turns a template Traverse has restructured into a visible sync error.
 *
 * Its thresholds are set from measured yields — 100% on every template except PROP's 31/32 — so the
 * cases that must *not* fire matter as much as the ones that must. A guard that cries wolf on a
 * handful of odd cards gets ignored, and then the real break gets ignored with it.
 */
class CardVocabularyTest {

    private fun cards(template: String, total: Int, read: Int) =
        List(total) { CardVocabulary.Outcome(template, it < read) }

    @Test
    fun `a template that reads nothing is a break at any size`() {
        // WORD CONNECTION has six cards, below any rate threshold worth having, so without this
        // it was never watched at all.
        assertNotNull(brokenTemplate(cards("WORD CONNECTION REVIEW", total = 6, read = 0)))
        assertNotNull(brokenTemplate(cards("MSLK Card", total = 649, read = 0)))
    }

    @Test
    fun `but not when there are too few cards to tell`() {
        assertNull(brokenTemplate(cards("WORD CONNECTION REVIEW", total = 2, read = 0)))
    }

    @Test
    fun `a large template that mostly fails is a break`() {
        assertNotNull(brokenTemplate(cards("MSLK Card", total = 649, read = 300)))
    }

    @Test
    fun `the measured real yields stay quiet`() {
        // Exactly what the live deck produces. If this ever fires the thresholds are wrong, not
        // the deck.
        val live = cards("/Mandarin_Blueprint/MSLK Card", 649, 649) +
            cards("/Mandarin_Blueprint/MB PM Cloze", 214, 214) +
            cards("/Mandarin_Blueprint/MOVIE REVIEW", 38, 38) +
            cards("/Mandarin_Blueprint/PROP REVIEW", 32, 31) +
            cards("/Mandarin_Blueprint/WORD CONNECTION REVIEW", 6, 6)

        assertNull(brokenTemplate(live))
    }

    @Test
    fun `every template that was fetched is watched`() {
        // Cards teaching a pinyin sound are excluded before the fetch, so anything that got this
        // far should have been readable. A template nobody has mapped reading nothing is exactly
        // the thing worth being told about — it is how the next 30,000 Language Islands cards
        // would have announced themselves.
        assertNotNull(brokenTemplate(cards("SOMETHING NEW", total = 50, read = 0)))
    }

    @Test
    fun `names the template that broke`() {
        val message = brokenTemplate(cards("MB PM Cloze", total = 214, read = 0))

        assertTrue(message.orEmpty(), message!!.contains("MB PM Cloze"))
        assertTrue(message, message.contains("214"))
    }

    @Test
    fun `an empty run is not a break`() {
        assertNull(brokenTemplate(emptyList()))
    }
}
