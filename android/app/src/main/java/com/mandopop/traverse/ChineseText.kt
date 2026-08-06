package com.mandopop.traverse

/**
 * Han characters, and the markup that Traverse wraps them in.
 *
 * Once cards were read by field name rather than by guessing which string looked like what, most of
 * this went away — the tests for "is this a link", "is this tone-marked pinyin" and the like existed
 * only to locate fields that are now simply addressed. What is left is the definition of a Han
 * character, shared so that no two readers can disagree about it.
 */
internal object ChineseText {

    /**
     * The main block, Extension A, CJK Strokes, and 〇.
     *
     * Not one range, and each addition is here because a real card needed it. 〇 is the ideographic
     * zero, which lives in CJK Symbols and Punctuation — a date written 二〇二六 has four characters
     * and four syllables, and omitting it puts the reading one short and discards the whole
     * sentence's alignment. CJK Strokes covers PROP cards that teach a bare stroke (㇉, ㇏): those
     * are the card's entire content, so leaving them out made the card read as empty.
     *
     * Supplementary-plane characters are still excluded (one PROP card, 𠂉). They are surrogate
     * pairs, so admitting them would make `String` indices stop matching character counts, and the
     * reading alignment is built on that equivalence.
     */
    private const val HAN_START = '一'
    private const val HAN_END = '鿿'
    private const val EXT_A_START = '㐀'
    private const val EXT_A_END = '䶿'
    private const val STROKE_START = '㇀'
    private const val STROKE_END = '㇯'
    private const val IDEOGRAPHIC_ZERO = '〇'

    private val HAN_RUN = Regex(
        "[$HAN_START-$HAN_END$EXT_A_START-$EXT_A_END$STROKE_START-$STROKE_END$IDEOGRAPHIC_ZERO]+",
    )
    private val HTML_TAG = Regex("<[^>]*>")

    /**
     * Spelled out rather than `\\s`, which means different things in different places: Android's
     * regex engine is ICU and matches Unicode spaces, the JVM's is ASCII-only. Cards do contain
     * non-breaking and ideographic spaces, so with `\\s` a unit test and the device disagreed about
     * what the very same card said.
     */
    private val WHITESPACE = Regex("[\\s\u00a0\u2000-\u200b\u3000]+")

    /** `\\-OR-` — cards escape markdown punctuation, and the backslash is not part of the text. */
    private val ESCAPE = Regex("\\\\(.)")

    /** `==祭奠==` — MB Sentence highlights the word a sentence teaches; the marks are not text. */
    private val HIGHLIGHT = Regex("==+")

    /** `在（1）` — a deck-authoring suffix that disambiguates two cards teaching the same word. */
    private val DISAMBIGUATOR = Regex("[（(]\\s*\\d+\\s*[）)]\\s*$")

    /** PROP cards append the character's stroke diagram to the character itself, as markdown. */
    private val MARKDOWN_LINK = Regex("!?\\[[^\\]]*\\]\\([^)]*\\)")

    private val ENTITIES = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
    )

    fun isHan(char: Char): Boolean =
        char in HAN_START..HAN_END ||
            char in EXT_A_START..EXT_A_END ||
            char in STROKE_START..STROKE_END ||
            char == IDEOGRAPHIC_ZERO

    fun hasHan(text: String): Boolean = text.any(::isHan)

    /** The Han characters of [text] in order, which is what a reading has to line up against. */
    fun hanOnly(text: String): String = text.filter(::isHan)

    fun hanRuns(text: String): List<String> = HAN_RUN.findAll(text).map { it.value }.toList()

    /**
     * [text] without surrounding punctuation, for asking the dictionary about it.
     *
     * 谢谢！ is a headword and 谢谢！is not, so a card that states a perfectly ordinary word with a
     * full stop on it was being classed as an utterance — which kept six real words out of the
     * notification and left Reveal nothing to look up. Only punctuation and whitespace are
     * stripped: digits and letters stay, so `1776年` does not quietly become 年.
     */
    fun trimPunctuation(text: String): String =
        DISAMBIGUATOR.replace(text, "").trim { !it.isLetterOrDigit() }

    /**
     * Field values are not uniformly formatted — MOVIE wraps its content in `<p>`, PROP appends an
     * image to the character itself, MSLK and Cloze are bare — so everything is flattened before it
     * is read.
     */
    fun stripMarkup(value: String): String {
        var text = ESCAPE.replace(MARKDOWN_LINK.replace(HTML_TAG.replace(value, " "), " "), "$1")
        text = HIGHLIGHT.replace(text, "")
        for ((entity, replacement) in ENTITIES) text = text.replace(entity, replacement)
        return WHITESPACE.replace(text, " ").trim()
    }
}
