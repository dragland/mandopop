package com.mandopop.traverse

/**
 * Shape tests for the strings on a Traverse card.
 *
 * Card documents keep their content in a `fields` map whose keys are opaque per-template slugs, so
 * a field is identified by what it looks like rather than by what it is called. That is a real
 * weakness — two lookalike strings misfire silently, where a renamed key would fail outright — so
 * every predicate here is written to be *exclusive*: callers take a field only when exactly one
 * string on the card matches, and readings are cross-checked against the character count before
 * being believed. Ambiguity yields nothing rather than a guess.
 */
internal object ChineseText {

    /**
     * Han characters: the main block, Extension A, and 〇.
     *
     * 〇 is the reason the set is not a single range — the ideographic zero lives in CJK Symbols and
     * Punctuation, but a date written 二〇二六 has four characters and four syllables, and leaving it
     * out would put the reading one short and throw the whole sentence's alignment away.
     */
    private const val HAN_START = '一'
    private const val HAN_END = '鿿'
    private const val EXT_A_START = '㐀'
    private const val EXT_A_END = '䶿'
    private const val IDEOGRAPHIC_ZERO = '〇'

    /** Marked vowels are what separates a pinyin reading from ordinary Latin text on a card. */
    private const val TONE_MARKS =
        "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿĀÁǍÀĒÉĚÈĪÍǏÌŌÓǑÒŪÚǓÙǕǗǙǛÜ"

    /** 儿 after another character is erhua — it colours the previous syllable, not its own. */
    private const val ERHUA = '儿'

    private val HAN_RUN = Regex("[$HAN_START-$HAN_END$EXT_A_START-$EXT_A_END$IDEOGRAPHIC_ZERO]+")
    private val HTML_TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")

    /** An apostrophe separates pinyin syllables (`xī'ān`), so it must split rather than vanish. */
    private val SYLLABLE_BREAK = Regex("['’]")

    /** Markdown links and images, `[text](target)` — the wrapped form of the paths above. */
    private val MARKDOWN_LINK = Regex("!?\\[[^\\]]*]\\([^)]*\\)")

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
     * Card fields mix bare text, markdown and HTML depending on template — MOVIE wraps everything
     * in `<p>`, MSLK and PM Cloze do not — so everything is flattened before it is inspected.
     */
    fun stripMarkup(value: String): String {
        var text = HTML_TAG.replace(value, " ")
        for ((entity, replacement) in ENTITIES) text = text.replace(entity, replacement)
        return WHITESPACE.replace(text, " ").trim()
    }

    /**
     * True for a link, path or embedded asset.
     *
     * These have to be discarded before anything else: card bodies reference other cards by path,
     * and some of those paths contain literal Han characters (`/Mandarin_Blueprint/人（HANZI）`),
     * which would otherwise read as the card's own vocabulary — and a second Han-bearing string is
     * not merely noise here, it makes the card ambiguous and so unreadable.
     *
     * Bare paths are only half of it: the same references also appear wrapped as markdown, both as
     * the target and as the link text. A string is a reference when nothing survives removing its
     * links, which leaves prose containing an inline link alone.
     */
    fun isReference(value: String): Boolean {
        val text = value.trim()
        if (text.startsWith("/") || text.contains("://")) return true
        return MARKDOWN_LINK.containsMatchIn(text) && MARKDOWN_LINK.replace(text, " ").isBlank()
    }

    /** A tone-marked reading, as opposed to English text or a bare identifier. */
    fun isPinyin(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty() || text.length > MAX_PINYIN_LENGTH) return false
        if (hasHan(text)) return false
        if (text.none { it in TONE_MARKS }) return false
        val tokens = syllables(text)
        if (tokens.isEmpty() || tokens.size > MAX_SYLLABLES) return false
        return tokens.all { token -> token.length <= MAX_SYLLABLE_LENGTH }
    }

    /** The syllables of a reading, with punctuation dropped and apostrophes treated as breaks. */
    fun syllables(value: String): List<String> =
        SYLLABLE_BREAK.replace(value, " ")
            .filter { it.isLetter() || it.isWhitespace() }
            .split(WHITESPACE)
            .filter { it.isNotBlank() }

    /**
     * [reading] laid out one entry per Han character, or null when it does not fit [text].
     *
     * Chinese is one syllable per character, which makes this a complete check rather than a
     * heuristic — a string that only *looked* like the reading almost never lines up. It is also
     * what lets a word cut out of a sentence take its own syllables instead of a dictionary guess.
     *
     * Erhua is the one place the invariant bends: 哪儿 is `nǎr`, two characters and one syllable. A
     * 儿 is treated as silent only when the remaining characters outnumber the remaining syllables,
     * so a 儿 that really is its own syllable (儿子, `ér zi`) still gets one. Its entry is blank
     * rather than absent, so positions after it stay correct.
     */
    fun alignReadings(text: String, reading: String?): List<String>? {
        if (reading.isNullOrBlank()) return null
        val characters = hanOnly(text)
        if (characters.isEmpty()) return null
        val syllables = syllables(reading)

        val readings = ArrayList<String>(characters.length)
        var next = 0
        for (index in characters.indices) {
            val absorbed = characters[index] == ERHUA && index > 0 &&
                characters.length - index > syllables.size - next
            if (absorbed) {
                readings += ""
                continue
            }
            if (next >= syllables.size) return null
            readings += syllables[next++]
        }
        return if (next == syllables.size) readings else null
    }

    private const val MAX_PINYIN_LENGTH = 240
    private const val MAX_SYLLABLES = 40
    private const val MAX_SYLLABLE_LENGTH = 6
}
