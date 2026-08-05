package com.mandopop.traverse

/** One word cut out of a string, with the index of its first character among the Han characters. */
data class Segment(val text: String, val hanIndex: Int)

/**
 * Splits unspaced Chinese into words by longest match against CC-CEDICT.
 *
 * Written Chinese has no word delimiters, so the 450 sentences the user drills only become
 * vocabulary once they are cut up. Longest-match is the standard first approximation and it is
 * correct on most of this deck (他很快吗 → 他 / 很 / 快 / 吗, 请吃东西 → 请 / 吃 / 东西).
 *
 * Where it goes wrong is boundary placement, not invention: every output is a substring of the
 * input, so no character appears that the user never saw. The failure is a genuine dictionary word
 * straddling two intended ones. Measured on this deck: 二十个人 "twenty people" cuts as 二十 / 个人,
 * and 个人 "individual, personal" is a real entry and the wrong reading of those characters. The
 * opposite case is benign — taking 但是 whole never records 但, under-crediting a character the user
 * plainly knows — and the whole thing is already visibly inconsistent, with 一个月 splitting as
 * 一个 / 月 but 三个月 as 三 / 个 / 月. Over ~700 words the output is short enough to read rather than
 * reason about, which is why nothing cleverer is warranted yet.
 */
object Segmenter {

    /** CC-CEDICT has longer entries, but past four characters they are idioms, not vocabulary. */
    const val MAX_WORD_LENGTH = 4

    /**
     * Real dictionary entries that, in this deck, only ever occur straddling two intended words.
     *
     * Longest match invents rather than omits: every constituent of these is separately in the
     * index already, so refusing them loses nothing and removes all 17 artifacts they produced —
     * 你妈 and 妈的 out of 你/妈妈/的, 个人 out of 二十/个/人, 不快 out of 不/快 ("not fast", 20 cards),
     * 在那儿 out of 在/那儿 (11).
     *
     * A list, because the alternatives were measured and are worse: backward maximum matching and
     * a minimise-single-characters DP each fix about five of these and break sixty, mostly by
     * destroying the number system (二十/三 becomes 二/十三). Frequency-weighted segmentation is
     * catastrophic, splitting 不是 and 好不好 into characters.
     *
     * The cost is the reverse error: if a sentence ever genuinely means "personal" by 个人, it will
     * be missed. Missing beats inventing here — the index answers "words I have been exposed to",
     * and a wrong entry is a wrong answer where a missing one is only an incomplete one.
     */
    private val NEVER_A_WORD_HERE = setOf(
        "你妈", "妈的", "要不", "要说", "我去", "不知", "在外", "吃藕",
        "吃的", "个人", "不快", "不大", "在那儿", "那是", "你好",
    )

    /**
     * Every multi-character substring worth testing for dictionary membership.
     *
     * Collected across all cards up front so membership resolves in a handful of batched queries
     * instead of one round trip per candidate.
     */
    fun candidates(text: String): Set<String> {
        val found = mutableSetOf<String>()
        for (run in ChineseText.hanRuns(text)) {
            for (start in run.indices) {
                val last = minOf(start + MAX_WORD_LENGTH, run.length)
                for (end in start + 2..last) found += run.substring(start, end)
            }
        }
        return found
    }

    /**
     * Cuts [text] into words, longest first.
     *
     * Single characters are always emitted, dictionary word or not — a lone component still came
     * off a card the user studied, and dropping it would silently shorten the sentence.
     * [hanIndex] counts Han characters only, so it lines up with a syllable-per-character reading
     * even when the sentence contains digits or punctuation.
     */
    fun segment(text: String, isWord: (String) -> Boolean): List<Segment> {
        val segments = mutableListOf<Segment>()
        var hanIndex = 0
        for (run in ChineseText.hanRuns(text)) {
            var start = 0
            while (start < run.length) {
                var length = 1
                val longest = minOf(MAX_WORD_LENGTH, run.length - start)
                for (candidate in longest downTo 2) {
                    val word = run.substring(start, start + candidate)
                    if (word !in NEVER_A_WORD_HERE && isWord(word)) {
                        length = candidate
                        break
                    }
                }
                segments += Segment(run.substring(start, start + length), hanIndex + start)
                start += length
            }
            hanIndex += run.length
        }
        return segments
    }

    /**
     * The syllables sitting over [segment]'s own characters.
     *
     * This is why a segmented sentence yields real readings rather than dictionary guesses: the
     * card states the whole sentence's pinyin, and [Pinyin.align] has already laid it
     * out one entry per character, so a word inside it can simply take its own slice. Pass the
     * aligned list, never raw syllables — an unchecked reading would shift every word after a
     * mismatch, and erhua would shift everything after it.
     */
    fun readingFor(segment: Segment, alignedReadings: List<String>?): String? {
        if (alignedReadings == null) return null
        val end = segment.hanIndex + segment.text.length
        if (segment.hanIndex < 0 || end > alignedReadings.size) return null
        return alignedReadings.subList(segment.hanIndex, end)
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
    }
}
