package com.mandopop.traverse

/**
 * What a card turned out to say.
 *
 * [hanzi] is the text as the card writes it — a word for most templates, a whole sentence for MSLK.
 * [pinyin] and [english] come from the card when it states them and it can be shown they belong to
 * [hanzi]; null means "ask the dictionary", never "this card has no reading".
 */
data class ParsedCard(
    val hanzi: String?,
    val pinyin: String?,
    val english: String?,
    val isSentence: Boolean,
) {
    companion object {
        val EMPTY = ParsedCard(null, null, null, isSentence = false)
    }
}

/**
 * Reads a card according to its template.
 *
 * Cards carry named fields — `Chinese`, `Pinyin`, `English Translation`, `HANZI`, `KEYWORD` — so
 * this addresses them by name. A renamed field then yields nothing at all for that template, which
 * the parse-rate guard turns into a visible error, rather than quietly reading the wrong string.
 *
 * **Names locate the pair; shape decides which is which.** On MSLK cards the course has `Chinese`
 * and `Pinyin` swapped — and not consistently: 130 of 160 sampled cards hold the hanzi under
 * `Pinyin`, the other 30 under `Chinese`. Trusting either name would get most of the deck exactly
 * backwards, so the field holding Han characters is the Chinese whatever it is called.
 *
 * A reading is only kept if [Pinyin.align] can lay it out one syllable per character. That is a
 * complete check rather than a heuristic, and it is what makes a word cut out of a sentence able
 * to take its own syllables instead of a dictionary guess.
 */
object CardParser {

    /**
     * Bump on any change to what this file extracts.
     *
     * Rows below the current version are treated as stale by the backfill, so a parser fix
     * republishes itself across the whole deck on the next sync. This is the only mechanism that
     * repairs a card cached as unreadable — without it, a parse bug is permanent.
     */
    const val VERSION = 3

    /**
     * Which fields hold what, per template.
     *
     * [written] and [spoken] are candidates for the *pair*, not assignments — see the class note on
     * the MSLK swap. Aliases exist because a few cards carry both `WORD` and `Word`; lookup is
     * case-insensitive, so these only cover genuinely different names.
     */
    private data class Layout(
        val written: Array<String>,
        val spoken: Array<String>,
        val meaning: Array<String>,
        val alwaysSentence: Boolean = false,
    )

    private val LAYOUTS = mapOf(
        "MSLK" to Layout(
            written = arrayOf("Chinese", "Pinyin"),
            spoken = arrayOf("Pinyin", "Chinese"),
            meaning = arrayOf("English Translation", "English"),
            // Always, even for a one-word phrase: MSLK teaches the utterance, and its English is a
            // sentence translation rather than a headword gloss.
            alwaysSentence = true,
        ),
        "Cloze" to Layout(
            written = arrayOf("Characters"),
            spoken = arrayOf("Pinyin"),
            meaning = arrayOf("English"),
        ),
        "MOVIE" to Layout(
            written = arrayOf("HANZI"),
            spoken = arrayOf("PINYIN"),
            meaning = arrayOf("KEYWORD"),
        ),
        "WORD CONNECTION" to Layout(
            written = arrayOf("WORD"),
            spoken = arrayOf("PINYIN"),
            meaning = arrayOf("MEANING"),
        ),
        // PROP cards name a mnemonic prop ("Toilet"), not a translation, so the meaning is left to
        // CC-CEDICT — which mostly has nothing either, these being strokes and components.
        "PROP" to Layout(
            written = arrayOf("COMPONENT"),
            spoken = arrayOf(),
            meaning = arrayOf(),
        ),
    )

    /**
     * Whether this template has a rule of its own.
     *
     * Callers must not fall back to the generic scan for a template that returns true here: MOVIE
     * cards name their mnemonic props in the body (中 references 口 and 丨), and scraping those is
     * the same failure that got ACTOR and SET excluded in the first place. Better an empty row that
     * the parse-rate guard can see than a plausible wrong one.
     */
    fun handles(template: String): Boolean = layoutFor(template) != null

    fun parse(template: String, doc: CardDoc?): ParsedCard {
        if (doc == null) return ParsedCard.EMPTY
        // The document's own template beats the caller's: a card with two prompt rows has two
        // schedule rows, and choosing one of them to decide how to read the card is arbitrary.
        val layout = layoutFor(doc.template ?: template) ?: return ParsedCard.EMPTY

        val first = doc.field(*layout.written)
        val second = doc.field(*layout.spoken)
        val hanzi = listOfNotNull(first, second).firstOrNull(ChineseText::hasHan)
            ?.let(ChineseText::stripMarkup)
            ?.takeIf { ChineseText.hasHan(it) }
            ?: return ParsedCard.EMPTY
        val reading = listOfNotNull(first, second)
            .firstOrNull { !ChineseText.hasHan(it) }
            ?.let(ChineseText::stripMarkup)

        val characters = ChineseText.hanOnly(hanzi).length
        return ParsedCard(
            hanzi = hanzi,
            pinyin = Pinyin.align(hanzi, reading)?.filter { it.isNotBlank() }?.joinToString(" "),
            english = doc.field(*layout.meaning)?.let(ChineseText::stripMarkup),
            isSentence = layout.alwaysSentence || characters > Segmenter.MAX_WORD_LENGTH,
        )
    }

    /**
     * Matched on substrings because Traverse qualifies templates with the course in some places
     * and not others (`/Mandarin_Blueprint/MSLK Card` vs `MSLK Card`).
     *
     * ACTOR and SET are absent deliberately — they teach a pinyin sound and are excluded before
     * the fetch. Anything else falls through to [HanziExtractor]'s scan.
     */
    private fun layoutFor(template: String): Layout? =
        LAYOUTS.entries.firstOrNull { template.contains(it.key, ignoreCase = true) }?.value
}
