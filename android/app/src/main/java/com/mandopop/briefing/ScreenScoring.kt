package com.mandopop.briefing

import com.mandopop.traverse.ChineseText
import com.mandopop.traverse.Segmenter

/**
 * "How much of this screen can I read" (spec.md §4.4), scored at shade-pull over the rolling
 * snapshot. Token-level: segments known / segments total, which is coverage, not comprehension —
 * the notification's copy must not imply fluency (Nation's ~98% threshold for comfortable
 * reading is the caveat of record). Screens without enough Chinese produce no score at all:
 * a percentage over four characters is noise wearing a number.
 */
object ScreenScoring {

    private const val MIN_HAN_CHARS = 20

    data class Score(val percentKnown: Int, val totalWords: Int)

    fun score(
        text: String,
        isWord: (String) -> Boolean,
        isKnown: (String) -> Boolean,
    ): Score? {
        if (ChineseText.hanOnly(text).length < MIN_HAN_CHARS) return null
        val segments = Segmenter.segment(text, isWord)
        if (segments.isEmpty()) return null
        val known = segments.count { isKnown(it.text) }
        return Score(
            percentKnown = (known * 100) / segments.size,
            totalWords = segments.size,
        )
    }
}
