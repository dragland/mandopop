package com.mandopop.briefing

import com.mandopop.traverse.ChineseText
import com.mandopop.traverse.Segmenter

/**
 * The screen comprehension line (spec.md §4.4), scored at shade-pull over the rolling snapshot.
 *
 * Two flavors, because the metric depends on what the screen is:
 * - Chinese text → **readable %**: segments known / segments total. Coverage, not
 *   comprehension — the copy must not imply fluency (Nation's ~98% threshold for comfortable
 *   reading is the caveat of record).
 * - English text (most of this phone's reading) → **sayable %**: content words whose
 *   dictionary mapping lands in the known vocabulary — "how much of this could you say in
 *   Chinese." The denominator is words the dictionary can map at all, so proper nouns and
 *   jargon don't drag the score for words no learner is expected to have.
 *
 * Screens without enough of either produce no score: a percentage over four tokens is noise
 * wearing a number.
 */
object ScreenScoring {

    private const val MIN_HAN_CHARS = 20
    private const val MIN_MAPPED_WORDS = 8
    private const val MAX_LOOKUPS = 30

    enum class Flavor { READABLE, SAYABLE }

    data class Score(val flavor: Flavor, val percentKnown: Int, val totalWords: Int)

    fun readable(
        text: String,
        isWord: (String) -> Boolean,
        isKnown: (String) -> Boolean,
    ): Score? {
        if (ChineseText.hanOnly(text).length < MIN_HAN_CHARS) return null
        val segments = Segmenter.segment(text, isWord)
        if (segments.isEmpty()) return null
        val known = segments.count { isKnown(it.text) }
        return Score(Flavor.READABLE, (known * 100) / segments.size, segments.size)
    }

    suspend fun sayable(
        text: String,
        isKnown: (String) -> Boolean,
        lookup: suspend (String) -> String?,
    ): Score? {
        val words = BriefingPicker.contentWords(text, limit = MAX_LOOKUPS)
        var mapped = 0
        var known = 0
        for (word in words) {
            val hanzi = lookup(word) ?: continue
            mapped++
            if (isKnown(hanzi)) known++
        }
        if (mapped < MIN_MAPPED_WORDS) return null
        return Score(Flavor.SAYABLE, (known * 100) / mapped, mapped)
    }

    /**
     * The stat lines are themselves Chinese — chrome the user reads dozens of times a day is
     * free study material. Pattern shared with the coverage stat: subject · ≈percent · verb.
     * 看得懂 "can read-and-understand it", 会说 "can say it".
     */
    fun line(score: Score): String = when (score.flavor) {
        Flavor.READABLE -> "屏幕 ≈${score.percentKnown}% 看得懂"
        Flavor.SAYABLE -> "屏幕 ≈${score.percentKnown}% 会说"
    }
}
