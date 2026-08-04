package com.mandopop.traverse

/**
 * Pulls candidate hanzi out of a Traverse card whose layout we have not mapped.
 *
 * The fallback behind [CardParser], and the only reader for PROP and WORD CONNECTION cards. Those
 * carry a bare word and nothing else — no reading, no gloss — so there is nothing a per-template
 * rule would recover, and scanning every string for CJK runs and letting CC-CEDICT arbitrate reads
 * them correctly. Membership is the filter: a wrong guess simply fails to resolve.
 *
 * Not used for templates [CardParser] handles. It cannot tell a card's own word from one quoted in
 * a mnemonic beyond preferring the title, which is exactly how MOVIE cards leak their props.
 */
object HanziExtractor {

    /** Longer than this is a sentence or an example, not the word the card teaches. */
    private const val MAX_CANDIDATE_LENGTH = 4

    /**
     * Candidate words, best first.
     *
     * [primary] (the card title or id) is trusted over body text, since a card's own name is far
     * more likely to be the word it teaches than something quoted in a mnemonic. Within each
     * source, multi-character words rank above bare characters: Mandarin Blueprint teaches
     * components alongside words, and a 2-character word is the more useful thing to surface.
     */
    fun candidates(primary: String?, others: List<String>): List<String> {
        val ranked = LinkedHashSet<String>()
        for (run in runsIn(primary)) ranked += run
        for (value in others) for (run in runsIn(value)) ranked += run
        return ranked.sortedWith(
            compareBy(
                { if (it in runsIn(primary)) 0 else 1 },
                { if (it.length == 1) 1 else 0 },
                { it.length },
            ),
        )
    }

    private fun runsIn(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        // Shares ChineseText's definition of a Han character rather than keeping a second copy of
        // the range: the two drifting apart would mean one reader seeing a character the other
        // does not, on the same card.
        return ChineseText.hanRuns(value).filter { it.length <= MAX_CANDIDATE_LENGTH }
    }
}
