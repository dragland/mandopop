package com.mandopop.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parity with `exactGlossRank` in `lib/normalize.js`. The lookup algorithm is written once per
 * platform, so the cases are shared rather than the code.
 */
class GlossMatchTest {
    @Test
    fun `matches the shared gloss rank fixtures`() {
        for (case in SharedFixtures.glossRankCases()) {
            assertEquals(case.name, case.expected, GlossMatch.rankOf(case.definitions, case.key))
        }
    }
}
