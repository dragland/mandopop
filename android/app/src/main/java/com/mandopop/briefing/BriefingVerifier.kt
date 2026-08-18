package com.mandopop.briefing

import com.mandopop.traverse.ChineseText
import com.mandopop.traverse.Segmenter

/**
 * The mechanical gate every briefing sentence passes, whoever composed it.
 *
 * The guarantee lives here, not in the prompt: segment the sentence with the same segmenter the
 * vocabulary index uses, then require every word to be in the user's known vocabulary or be the
 * plan's single frontier word. The model never grades itself, and a template is checked exactly
 * as hard as a generation. Doubt rejects — a refused sentence falls back to the next candidate,
 * and "correct but boring" beats fluent-but-unknown (spec.md §2: correct Chinese over clever
 * Chinese).
 *
 * Pure: membership arrives as functions, so the policy is unit-testable on the JVM.
 */
object BriefingVerifier {

    sealed interface Verdict {
        data object Pass : Verdict
        data class Fail(val reason: String, val unknownWords: List<String> = emptyList()) : Verdict
    }

    /**
     * [isWord] decides segmentation (dictionary ∪ allowed, so a known word missing from CC-CEDICT
     * still segments as itself); [isAllowed] decides acceptance (known ∪ the frontier word).
     */
    fun verify(
        sentence: String,
        isWord: (String) -> Boolean,
        isAllowed: (String) -> Boolean,
    ): Verdict {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return Verdict.Fail("empty")
        if (trimmed.length > MAX_LENGTH) return Verdict.Fail("too long (${trimmed.length} chars)")
        // Latin means the model leaked English, pinyin, or an app name into the sentence — all
        // things the notification must never teach.
        if (trimmed.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            return Verdict.Fail("contains Latin text")
        }
        if (!ChineseText.hasHan(trimmed)) return Verdict.Fail("no Chinese characters")

        val unknown = Segmenter.segment(trimmed, isWord)
            .map { it.text }
            .filterNot(isAllowed)
            .distinct()
        if (unknown.isNotEmpty()) {
            return Verdict.Fail("uses words the user has not learned: ${unknown.joinToString("、")}", unknown)
        }
        return Verdict.Pass
    }

    /** One line of shade real estate; anything longer is a paragraph, not a briefing. */
    private const val MAX_LENGTH = 40
}
