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
) {
    companion object {
        val EMPTY = ParsedCard(null, null, null)
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
    const val VERSION = 8

    /**
     * Which fields hold what — matched on the *fields a card has*, not on its template name.
     *
     * The course has 21 templates over 55,460 cards, and three of them are named by opaque slug
     * (`cFEA3bL9RCnkfp8nSu9x`) while being ordinary movie or vocabulary cards underneath. Keying on
     * names would mean enumerating those and every future one; keying on the fields themselves
     * reads all 21 today and anything sharing a shape tomorrow.
     *
     * [written] and [spoken] are candidates for the *pair*, not assignments. MSLK has `Chinese` and
     * `Pinyin` swapped on most of its cards while Language Islands and TPV — 30,000 cards of the
     * same shape — have them the right way round, so whichever field holds Han characters is the
     * Chinese, whatever it is called.
     *
     * Order matters where a card has several: an MB Sentence carries both `Sentence` and `Word`,
     * and the word is what it teaches.
     */
    private data class Layout(
        val written: Array<String>,
        val spoken: Array<String>,
        val meaning: Array<String>,
        /** One word expected, so take the first Han run — `祭奠 1` is 祭奠 with a deck index on it. */
        val word: Boolean = false,
    )

    private val LAYOUTS = listOf(
        // MOVIE REVIEW, and the same card under two slug templates.
        Layout(arrayOf("HANZI"), arrayOf("PINYIN"), arrayOf("KEYWORD"), word = true),
        // MB PM Cloze. Not `word`: some are dialogues spanning two speakers.
        Layout(arrayOf("Characters"), arrayOf("Pinyin"), arrayOf("English")),
        // MSLK, Language Islands (Production and Comprehension), TPV, Conversation Connectors.
        Layout(
            arrayOf("Chinese", "Chinese Phrase"),
            arrayOf("Pinyin", "Chinese"),
            arrayOf("English Translation", "English"),
        ),
        // WORD CONNECTION, MB Basic, and MB Sentence — which states the word it teaches outright,
        // so there is no need to find it by segmenting the sentence it also carries.
        Layout(
            arrayOf("WORD", "Word"),
            arrayOf("PINYIN", "Pinyin"),
            arrayOf("MEANING", "English", "Usage Definition"),
            word = true,
        ),
        // MB Sentence variants with no `Word`, where the taught span is marked `==like this==`.
        Layout(arrayOf("Sentence"), arrayOf(), arrayOf("Usage Definition", "English")),
        // PROP. Its `PROP` field names a mnemonic object, not a translation.
        Layout(arrayOf("COMPONENT"), arrayOf(), arrayOf(), word = true),
    )

    /**
     * Whether this card has a shape we can read.
     *
     * Templates that teach a pinyin sound rather than a word — ACTOR, SET, Minimal Pairs — carry no
     * field on this list, so they answer false without being named. They are still excluded before
     * the fetch, which saves the reads.
     */
    fun handles(doc: CardDoc?): Boolean = doc != null && layoutFor(doc) != null

    fun parse(doc: CardDoc?): ParsedCard {
        val layout = doc?.let(::layoutFor) ?: return ParsedCard.EMPTY

        val first = doc.field(*layout.written)
        val second = doc.field(*layout.spoken)
        val written = listOfNotNull(first, second).firstOrNull(ChineseText::hasHan)
            ?.let(ChineseText::stripMarkup)
            ?.takeIf { ChineseText.hasHan(it) }
            ?: return ParsedCard.EMPTY
        val hanzi = if (layout.word) ChineseText.hanRuns(written).first() else written
        val reading = listOfNotNull(first, second)
            .firstOrNull { !ChineseText.hasHan(it) }
            ?.let(ChineseText::stripMarkup)

        return ParsedCard(
            hanzi = hanzi,
            pinyin = Pinyin.align(hanzi, reading)?.filter { it.isNotBlank() }?.joinToString(" "),
            english = doc.field(*layout.meaning)?.let(ChineseText::stripMarkup),
        )
    }

    /** The first layout whose fields this card actually has. */
    private fun layoutFor(doc: CardDoc): Layout? =
        LAYOUTS.firstOrNull { doc.field(*it.written) != null }
}
