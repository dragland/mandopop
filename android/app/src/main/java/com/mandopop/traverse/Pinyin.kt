package com.mandopop.traverse

/**
 * Lines a card's reading up with the characters it belongs to, one entry per character.
 *
 * Cards do not write pinyin one token per character — Mandarin Blueprint groups it by word
 * (`Nǐ méiyǒu chī dōngxi, duì bu duì？` for 你没有吃东西，对不对？), so counting whitespace-separated
 * tokens rejected 60% of the deck's readings. Splitting a group into syllables needs a syllable
 * inventory, and the character count makes the split unambiguous: of the ways `méiyǒu` could be
 * cut, only one yields the number of syllables there are characters to receive.
 *
 * That count check is also what keeps the whole extraction honest. A string that merely *looked*
 * like a reading almost never divides into exactly the right number of valid syllables, so this
 * doubles as the test that the right field was read at all — see [CardParser].
 *
 * Verified against 246 real cards: 160/160 MSLK, 20/20 MOVIE, 6/6 WORD CONNECTION, 57/60 cloze.
 * The three holdouts are cards offering two alternative readings ("or"), which have no single
 * correct alignment and are supposed to fail.
 */
internal object Pinyin {

    /**
     * Every syllable, built from initials × finals rather than listed.
     *
     * Checked against CC-CEDICT: this covers every reading in the dictionary. The only things it
     * excludes are entries that were never Chinese — `ok`, `call`, and bare Latin letters read as
     * letters — which a frequency-filtered list kept while dropping rare-but-real syllables like
     * `zhei`.
     */
    private val INITIALS = listOf(
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w", "",
    )
    private val FINALS = listOf(
        "a", "o", "e", "ê", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "ong", "er",
        "i", "ia", "ie", "iao", "iu", "ian", "in", "iang", "ing", "iong", "io",
        "u", "ua", "uo", "uai", "ui", "uan", "un", "uang", "ueng",
        // `ue` is the written form of üe after j/q/x/y and (in CC-CEDICT) after n/l: xué, yuè,
        // jué, què, lüe, nüe. Omitting it cost 3,500+ dictionary readings and, worse, cost them
        // *silently* — a card whose reading contained 学 or 月 simply failed to align and fell
        // back to the dictionary, which looks the same as a card that stated no reading.
        "ue", "v", "ve", "van", "vn", "n", "ng", "m",
    )

    private val SYLLABLES: Set<String> =
        INITIALS.flatMapTo(mutableSetOf()) { initial -> FINALS.map { initial + it } }

    /** A syllable starting with one of these mid-word is what the apostrophe rule guards against. */
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'v')

    /** `zhèr`, `nǎr`: 这儿 and 哪儿 are two characters and one syllable. */
    private val CONTRACTED: Set<String> = SYLLABLES.mapTo(mutableSetOf()) { it + "r" }

    private const val ERHUA = '儿'
    private const val MAX_SYLLABLE = 7

    /** Only used to make the visited-state key unique; no group comes near it. */
    private const val MAX_GROUP = 64

    private val LETTERS = "A-Za-züÀ-ɏḀ-ỿ"
    private val NOT_LETTER = Regex("[^$LETTERS]+")

    /**
     * `A:` / `B:` — dialogue cards mark their speakers on both sides.
     *
     * Not anchored to a line start: markup stripping collapses the blank line between the two
     * turns, so by the time this runs only the first speaker is at the head of anything. Multi-
     * letter too — one card labels its three answers `Which:` / `That:` / `This:`, and a Latin word
     * before a colon is never part of a reading.
     */
    private val SPEAKER = Regex("(^|\\s)[A-Za-z]+\\s*[:：]")

    /** `Nà/Nèi` offers two readings for one character; the first is the card's own answer. */
    private val ALTERNATIVE = Regex("([$LETTERS]+)/[$LETTERS]+")

    /**
     * `{{c1::míng}}{{c2::tiān}}` — the cloze blanks *are* the reading.
     *
     * Replaced with nothing between them, not a space: 玩儿 is blanked as `{{c1::wán}}{{c2::r}}`,
     * and separating those leaves a bare `r`, which is not a syllable. Running them together costs
     * nothing, because the splitter takes grouped readings apart anyway.
     */
    private val CLOZE = Regex("\\{\\{c\\d+::(.*?)\\}\\}")

    /** Some cards offer two phrasings joined by a literal `or`, on both sides of the card. */
    private val ALTERNATION = Regex("(^|\\s)-?[oO][rR]-?($|\\s)")

    /**
     * One reading per Han character in [text], or null when [reading] does not fit it.
     *
     * A contracted erhua syllable covers its 儿 as well, and that 儿 gets a blank entry rather than
     * no entry, so every later character still lines up with its own syllable.
     */
    fun align(text: String, reading: String?): List<String>? {
        if (reading.isNullOrBlank()) return null
        val characters = ChineseText.hanOnly(text)
        if (characters.isEmpty()) return null

        var normalised = CLOZE.replace(reading, "$1")
        normalised = ALTERNATION.replace(SPEAKER.replace(normalised, " "), " ")
        normalised = ALTERNATIVE.replace(normalised, "$1")
        val groups = NOT_LETTER.split(normalised).filter { it.isNotBlank() }
        if (groups.isEmpty()) return null

        val out = ArrayList<String>(characters.length)
        return if (assign(groups, characters, out)) out else null
    }

    /**
     * Walks characters and reading together, trying every syllable boundary until one fits.
     *
     * One search, not a syllable-count loop feeding a separate cutter. The two-stage version could
     * only ever see the cutter's *first* decomposition for a given count, and there are three
     * spellings on this deck where that one is the wrong one: `zhōurì` cuts as `zhour|i` before
     * `zhou|ri`, `Shíèryuè` as `shier|…` before `shi|er|yue`, and `sāngè` as `sāng|è` before
     * `sān|gè`. The first two were rejected downstream and the card lost its reading entirely; the
     * third was *accepted*, silently, because both halves are spellable syllables.
     *
     * Backtracking is what fixes all three, because whether a cut is right can only be judged by
     * whether the rest of the sentence still fits. Failed states are remembered so a long sentence
     * cannot make this exponential.
     */
    private fun assign(
        groups: List<String>,
        characters: String,
        out: MutableList<String>,
    ): Boolean {
        // Two passes. Some groups have more than one valid decomposition and the character count
        // does not separate them — `sāngè` is both `sān|gè` and `sāng|è`, and the wrong one was
        // being taken silently. Pinyin's own apostrophe rule is the tiebreak: a syllable starting
        // with a vowel inside a word is exactly the case the language writes `xī'ān` to avoid, so
        // prefer decompositions without one. 二 really is vowel-initial (`shí|èr`), which is why
        // the restriction is a preference and not a rule.
        return assign(groups, characters, out, strict = true) ||
            assign(groups, characters, out, strict = false)
    }

    private fun assign(
        groups: List<String>,
        characters: String,
        out: MutableList<String>,
        strict: Boolean,
    ): Boolean {
        val bare = groups.map(::toneless)
        val dead = HashSet<Int>()
        out.clear()

        fun search(groupIndex: Int, offset: Int, charIndex: Int): Boolean {
            if (groupIndex == groups.size) return charIndex == characters.length
            if (offset == bare[groupIndex].length) return search(groupIndex + 1, 0, charIndex)
            if (charIndex == characters.length) return false

            val state = (groupIndex * MAX_GROUP + offset) * (characters.length + 1) + charIndex
            if (!dead.add(state)) return false

            val syllables = bare[groupIndex]
            val longest = minOf(MAX_SYLLABLE, syllables.length - offset)
            for (length in longest downTo 1) {
                val piece = syllables.substring(offset, offset + length)
                // A trailing -r followed by a 儿 is that 儿. Without the second half of this test a
                // spelling that is only a syllable *as* a contraction — `zher` is zh + er — would
                // take one character where it should take two.
                val contracted = piece in CONTRACTED &&
                    charIndex + 1 < characters.length && characters[charIndex + 1] == ERHUA
                if (piece !in SYLLABLES && !contracted) continue
                if (strict && offset > 0 && piece.first() in VOWELS) continue
                val width = if (contracted) 2 else 1
                if (charIndex + width > characters.length) continue

                out += groups[groupIndex].substring(offset, offset + length)
                if (contracted) out += ""
                if (search(groupIndex, offset + length, charIndex + width)) return true
                repeat(width) { out.removeAt(out.size - 1) }
            }
            return false
        }
        return search(0, 0, 0)
    }

    /** Tone marks off, `ü` folded to `v`, so a syllable can be looked up by spelling alone. */
    private fun toneless(value: String): String = buildString(value.length) {
        for (char in value.lowercase()) {
            val base = TONE_FOLD[char] ?: char
            if (base != ' ') append(base)
        }
    }

    private val TONE_FOLD: Map<Char, Char> = buildMap {
        fun fold(marked: String, base: Char) = marked.forEach { put(it, base) }
        fold("āáǎà", 'a')
        fold("ēéěèê", 'e')
        fold("īíǐì", 'i')
        fold("ōóǒò", 'o')
        fold("ūúǔù", 'u')
        fold("ǖǘǚǜü", 'v')
        fold("ńňǹ", 'n')
        fold("ḿ", 'm')
    }
}
