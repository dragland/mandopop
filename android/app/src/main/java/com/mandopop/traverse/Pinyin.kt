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
        "v", "ve", "van", "vn", "n", "ng", "m",
    )

    private val SYLLABLES: Set<String> =
        INITIALS.flatMapTo(mutableSetOf()) { initial -> FINALS.map { initial + it } }

    /** `zhèr`, `nǎr`: 这儿 and 哪儿 are two characters and one syllable. */
    private val CONTRACTED: Set<String> = SYLLABLES.mapTo(mutableSetOf()) { it + "r" }

    private const val ERHUA = '儿'
    private const val MAX_SYLLABLE = 7
    private const val MAX_GROUP_SYLLABLES = 8

    private val LETTERS = "A-Za-züÀ-ɏḀ-ỿ"
    private val NOT_LETTER = Regex("[^$LETTERS]+")

    /**
     * `A:` / `B:` — dialogue cards mark their speakers on both sides.
     *
     * Not anchored to a line start: markup stripping collapses the blank line between the two
     * turns, so by the time this runs only the first speaker is at the head of anything.
     */
    private val SPEAKER = Regex("(^|\\s)[A-Za-z]\\s*[:：]")

    /** `Nà/Nèi` offers two readings for one character; the first is the card's own answer. */
    private val ALTERNATIVE = Regex("([$LETTERS]+)/[$LETTERS]+")

    /** `{{c1::míng}}{{c2::tiān}}` — the cloze blanks *are* the reading. */
    private val CLOZE = Regex("\\{\\{c\\d+::(.*?)\\}\\}")

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

        val normalised = ALTERNATIVE.replace(SPEAKER.replace(CLOZE.replace(reading, "$1 "), " "), "$1")
        val groups = NOT_LETTER.split(normalised).filter { it.isNotBlank() }
        if (groups.isEmpty()) return null

        val out = ArrayList<String>(characters.length)
        return if (assign(groups, 0, characters, 0, out)) out else null
    }

    /**
     * Walks groups against characters, trying each way of cutting a group into syllables.
     *
     * Backtracking rather than greedy, because the split of one group can only be judged by
     * whether the rest of the sentence then fits — `zhei` and `zhe`+`i` are both spellable.
     */
    private fun assign(
        groups: List<String>,
        groupIndex: Int,
        characters: String,
        charIndex: Int,
        out: MutableList<String>,
    ): Boolean {
        if (groupIndex == groups.size) return charIndex == characters.length
        val group = groups[groupIndex]
        val bare = toneless(group)
        val remaining = characters.length - charIndex

        for (count in 1..minOf(MAX_GROUP_SYLLABLES, remaining)) {
            val cuts = cut(bare, count) ?: continue
            val added = ArrayList<String>(count)
            var offset = 0
            var char = charIndex
            var fits = true
            for (length in cuts) {
                val piece = bare.substring(offset, offset + length)
                val spelled = group.substring(offset, offset + length)
                offset += length
                // A trailing -r followed by a 儿 is that 儿, always. Testing "and this is not
                // otherwise a syllable" looked safer but is wrong: `zher` is spellable as zh + er,
                // so 这儿 took one character instead of two and dragged the rest of the sentence
                // out of alignment.
                val contracted = piece in CONTRACTED &&
                    char + 1 < characters.length && characters[char + 1] == ERHUA
                if (char + (if (contracted) 2 else 1) > characters.length) {
                    fits = false
                    break
                }
                added += spelled
                char++
                if (contracted) {
                    added += ""
                    char++
                }
            }
            if (!fits) continue
            out += added
            if (assign(groups, groupIndex + 1, characters, char, out)) return true
            repeat(added.size) { out.removeAt(out.size - 1) }
        }
        return false
    }

    /** Cuts [bare] into exactly [count] syllables, longest-first, or null if it cannot be done. */
    private fun cut(bare: String, count: Int): List<Int>? {
        if (count <= 0 || bare.isEmpty()) return null
        val memo = HashMap<Int, List<Int>?>()
        fun go(start: Int, left: Int): List<Int>? {
            if (start == bare.length) return if (left == 0) emptyList() else null
            if (left <= 0) return null
            val key = start * (MAX_GROUP_SYLLABLES + 1) + left
            memo[key]?.let { return it }
            if (memo.containsKey(key)) return null
            for (length in minOf(MAX_SYLLABLE, bare.length - start) downTo 1) {
                val piece = bare.substring(start, start + length)
                if (piece !in SYLLABLES && piece !in CONTRACTED) continue
                val rest = go(start + length, left - 1)
                if (rest != null) {
                    val result = listOf(length) + rest
                    memo[key] = result
                    return result
                }
            }
            memo[key] = null
            return null
        }
        return go(0, count)
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
