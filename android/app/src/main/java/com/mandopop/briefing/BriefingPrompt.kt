package com.mandopop.briefing

/**
 * The O(1) prompt (spec.md §5): an English gist, the handful of chosen Chinese words, and two
 * few-shot examples. The known-words list never enters the prompt — small models cannot obey a
 * stated allowlist and the list only grows; the allowlist lives in [BriefingVerifier]. The retry
 * carries a short negative list instead, which *is* followable.
 */
object BriefingPrompt {

    fun build(gist: String, words: List<String>, avoid: List<String> = emptyList()): String =
        buildString {
            append("You write one short, simple, natural Chinese sentence for a beginner.\n")
            append("Rules: simplified characters only. Build the sentence from the given words. ")
            append("Keep it under 15 characters. ")
            append("Output only the Chinese sentence - no pinyin, no translation, no quotes.\n\n")
            append("Topic: today's calendar event: \"Team meeting\" at 15:00\n")
            append("Words: 今天 下午 会议\n")
            append("Sentence: 你今天下午有一个会议。\n\n")
            append("Topic: a pending notification from Messages: \"Dinner Friday?\"\n")
            append("Words: 朋友 吃饭\n")
            append("Sentence: 朋友问你要不要一起吃饭。\n\n")
            if (avoid.isNotEmpty()) {
                append("Do not use these words: ${avoid.joinToString("、")}\n")
            }
            append("Topic: ").append(gist).append('\n')
            append("Words: ").append(words.joinToString(" ")).append('\n')
            append("Sentence:")
        }

    /**
     * The sentence out of whatever the model actually returned: first non-empty line, label and
     * quote wrappers stripped. Extraction stays dumb on purpose — anything it gets wrong is the
     * verifier's to refuse, not this function's to repair.
     */
    fun extractSentence(raw: String): String {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return line
            .removePrefix("Sentence:").removePrefix("sentence:").removePrefix("句子：")
            .trim()
            .trim('"', '“', '”', '‘', '’', '\'', '`')
            .trim()
    }
}
