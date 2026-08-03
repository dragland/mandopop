package com.mandopop.traverse

/**
 * Pulls candidate hanzi out of a Traverse card.
 *
 * Deliberately schema-agnostic. Only ~10% of cards identify themselves by hanzi; the rest use
 * opaque ids (`02v28c3t8af1cxvwokl8d25u`) and hide the character somewhere in a `fields` map whose
 * keys vary by card template. Rather than hardcode key names that Traverse can rename underneath
 * us, this scans every string on the card for CJK runs and lets the dictionary decide which one is
 * a real word — CC-CEDICT membership is the filter, so a wrong guess simply fails to resolve.
 */
object HanziExtractor {

    private val CJK_RUN = Regex("[\\u4e00-\\u9fff]+")

    /** Longer than this is a sentence or an example, not the word the card teaches. */
    private const val MAX_WORD_LENGTH = 4

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
        return CJK_RUN.findAll(value)
            .map { it.value }
            .filter { it.length <= MAX_WORD_LENGTH }
            .toList()
    }
}
