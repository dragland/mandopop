package com.mandopop.dictionary

/**
 * Whether a dictionary entry *means* an English word, or merely mentions it.
 *
 * Mirrors `exactGlossRank` in `lib/normalize.js`; the shared cases in
 * `testdata/gloss_rank_cases.tsv` keep the two from drifting. Both platforms need it because the
 * lookup algorithm is implemented once per platform even though the dictionary is shared.
 */
object GlossMatch {

    /** No sense equals the key. Ordered above every real position so comparisons read naturally. */
    const val NO_MATCH = Int.MAX_VALUE

    /** CC-CEDICT qualifies senses in parentheses: "(CL:隻|只[zhi1])", "(dialect)", "(coll.)". */
    private val ANNOTATION = Regex("\\([^)]*\\)")
    private val LEADING_ARTICLE = Regex("^(?:to|a|an|the)\\s+")
    private val TRAILING_PUNCTUATION = Regex("[.!?]+$")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Position of the sense that is exactly [key], or [NO_MATCH].
     *
     * Position matters as much as presence: common characters accumulate archaic senses, so
     * "any sense matches" would promote 门 for "school" and 牢 for "fast".
     */
    fun rankOf(definitions: List<String>, key: String): Int {
        var position = 0
        for (definition in definitions) {
            for (sense in definition.split(';')) {
                val normalized = sense
                    .lowercase()
                    .replace(ANNOTATION, " ")
                    .replace(WHITESPACE, " ")
                    .trim()
                    .replace(LEADING_ARTICLE, "")
                    .replace(TRAILING_PUNCTUATION, "")
                    .trim()
                if (normalized == key) return position
                position++
            }
        }
        return NO_MATCH
    }
}
