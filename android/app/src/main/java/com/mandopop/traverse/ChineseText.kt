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
     * The main block, Extension A, and 〇.
     *
     * 〇 is why this is not one range: the ideographic zero lives in CJK Symbols and Punctuation,
     * but a date written 二〇二六 is four characters with four syllables, and leaving it out would
     * put the reading one short and throw the whole sentence's alignment away.
     */
    private const val HAN_START = '一'
    private const val HAN_END = '鿿'
    private const val EXT_A_START = '㐀'
    private const val EXT_A_END = '䶿'
    private const val IDEOGRAPHIC_ZERO = '〇'

    private val HAN_RUN = Regex("[$HAN_START-$HAN_END$EXT_A_START-$EXT_A_END$IDEOGRAPHIC_ZERO]+")
    private val HTML_TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")

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
        char in HAN_START..HAN_END || char in EXT_A_START..EXT_A_END || char == IDEOGRAPHIC_ZERO

    fun hasHan(text: String): Boolean = text.any(::isHan)

    /** The Han characters of [text] in order, which is what a reading has to line up against. */
    fun hanOnly(text: String): String = text.filter(::isHan)

    fun hanRuns(text: String): List<String> = HAN_RUN.findAll(text).map { it.value }.toList()

    /**
     * Field values are not uniformly formatted — MOVIE wraps its content in `<p>`, PROP appends an
     * image to the character itself, MSLK and Cloze are bare — so everything is flattened before it
     * is read.
     */
    fun stripMarkup(value: String): String {
        var text = MARKDOWN_LINK.replace(HTML_TAG.replace(value, " "), " ")
        for ((entity, replacement) in ENTITIES) text = text.replace(entity, replacement)
        return WHITESPACE.replace(text, " ").trim()
    }
}
