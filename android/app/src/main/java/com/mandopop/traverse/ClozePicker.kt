package com.mandopop.traverse

import kotlin.math.abs

/**
 * Picks the sentence the due notification shows instead of a bare word (spec.md §4.1).
 *
 * A word met inside a real course sentence is a stronger retrieval prompt than the word alone,
 * and rotating between qualifying sentences per day strengthens it further (contextual
 * variability). The i+1 rule is what keeps it fair: every word in the shown sentence must be
 * known — the sentence is context for recalling the due word, never a smuggled vocabulary
 * lesson. No qualifying sentence → null, and the caller falls back to the bare word, which is
 * exactly the previous behaviour.
 *
 * Pure: membership arrives as functions, the rotation seed as a number, so the policy is
 * JVM-testable and repost-stable (same seed → same sentence all day).
 */
object ClozePicker {

    /** Longer than this stops being a glance and starts being a paragraph. */
    private const val MAX_SENTENCE_CHARS = 26

    fun pick(
        sentences: List<String>,
        dueWord: String,
        isWord: (String) -> Boolean,
        isKnown: (String) -> Boolean,
        seed: Int,
    ): String? {
        val viable = sentences.filter { sentence ->
            sentence.length <= MAX_SENTENCE_CHARS &&
                sentence.contains(dueWord) &&
                Segmenter.segment(sentence, isWord).all { isKnown(it.text) }
        }
        if (viable.isEmpty()) return null
        return viable[abs(seed % viable.size)]
    }

    /** Stateless daily rotation: stable across reposts, fresh across days and words. */
    fun seed(epochDay: Long, word: String): Int = (epochDay * 31 + word.hashCode()).toInt()
}
