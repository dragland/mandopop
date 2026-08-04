package com.mandopop.traverse

/**
 * What a card turned out to say.
 *
 * [hanzi] is the text as the card writes it — a word for most templates, a whole sentence for MSLK.
 * [pinyin] and [english] are only set when the card states them *unambiguously*; a null means "ask
 * the dictionary", never "this card has no reading".
 */
data class ParsedCard(
    val hanzi: String?,
    val pinyin: String?,
    val english: String?,
    val isSentence: Boolean,
) {
    companion object {
        val EMPTY = ParsedCard(null, null, null, isSentence = false)
    }
}

/**
 * Reads a card according to its template.
 *
 * The generic "scan every string for Han and let CC-CEDICT arbitrate" pass was the right call while
 * the schema was unknown, and it still backstops the templates below. But it throws away everything
 * the card knows: MSLK carries the sentence's full reading and translation, PM Cloze carries the
 * syllables as the answer to its own blank, and MOVIE carries a keyword. Recovering those beats
 * inferring them — the dictionary cannot tell which sense of 东西 a card meant, and the card can.
 *
 * A reading is only ever accepted when its syllable count matches the character count. That is what
 * keeps shape-based field detection honest: a misidentified string almost never lines up.
 */
object CardParser {

    /**
     * Bump on any change to what this file extracts.
     *
     * Rows below the current version are treated as stale by the backfill, so a parser fix
     * republishes itself across the whole deck on the next sync. This is the only mechanism that
     * repairs a card cached as unreadable — without it, a parse bug is permanent.
     */
    const val VERSION = 1

    /**
     * Whether this template has a rule of its own.
     *
     * Callers must not fall back to the generic scan for a template that returns true here: MOVIE
     * cards name their mnemonic props in the body (中 references 口 and 丨), and scraping those is
     * the same failure that got ACTOR and SET excluded in the first place. Better an empty row that
     * the parse-rate guard can see than a plausible wrong one.
     */
    fun handles(template: String): Boolean = kindOf(template) != Kind.GENERIC

    fun parse(template: String, doc: CardDoc?): ParsedCard {
        if (doc == null) return ParsedCard.EMPTY
        val fields = doc.strings
            .map(ChineseText::stripMarkup)
            .filter { it.isNotBlank() && !ChineseText.isReference(it) }
        val title = doc.title?.let(ChineseText::stripMarkup).orEmpty()

        return when (kindOf(template)) {
            Kind.SENTENCE -> parseSentence(title, fields)
            Kind.CLOZE -> parseCloze(title, fields)
            Kind.CHARACTER -> parseCharacter(title, fields)
            Kind.GENERIC -> ParsedCard.EMPTY
        }
    }

    /**
     * MSLK cards are English-to-Chinese production drills, so the title is the *English* prompt and
     * the Chinese lives in the body. That is also why the old title-first rule contributed nothing
     * here — it worked only because the fallback scan happened to find the sentence.
     */
    private fun parseSentence(title: String, fields: List<String>): ParsedCard {
        // Grouped by characters rather than deduplicated as strings: the same sentence appearing
        // twice with different trailing punctuation is not two candidates, and treating it as two
        // would make the card unreadable. Two genuinely different sentences still yield nothing.
        val written = fields.filter(ChineseText::hasHan).groupBy(ChineseText::hanOnly)
        if (written.size != 1) return ParsedCard.EMPTY
        val sentence = written.values.first().maxByOrNull { it.length } ?: return ParsedCard.EMPTY
        return ParsedCard(
            hanzi = sentence,
            pinyin = alignedReading(sentence, soleReading(fields)),
            english = title.takeIf { it.isNotBlank() },
            isSentence = true,
        )
    }

    /**
     * Pronunciation Mastery clozes hide the *reading*, not the word: the card shows 明天 and blanks
     * out `{{c1::míng}}{{c2::tiān}}`. So the answer is exactly the per-character reading we want,
     * and the prompt is the vocabulary.
     *
     * Titles are not always a single word — some are phrases (`在哪里？`), some pair a character
     * with an example (`渴 她渴了`), a few are dialogues. Taking the first Han run keeps the card's
     * own subject and leaves the example behind.
     */
    private fun parseCloze(title: String, fields: List<String>): ParsedCard {
        val hanzi = ChineseText.hanRuns(title).firstOrNull() ?: return ParsedCard.EMPTY
        val reading = fields.firstOrNull { it.contains(CLOZE_MARKER) }
            ?.let { CLOZE.findAll(it).map { match -> match.groupValues[1].trim() }.toList() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
        return ParsedCard(
            hanzi = hanzi,
            pinyin = alignedReading(hanzi, reading),
            // The card's English sits in an unlabelled field next to several other bare strings
            // (`front-0`, `any`, the course name) with nothing to tell them apart, so this one is
            // left to CC-CEDICT — see the note in sync.md on shape-based field detection.
            english = null,
            // Borrowed from the segmenter on purpose: "too long to be a word" and "too long for
            // longest match to find" have to agree, or a title lands as a headword with no gloss
            // while its 4-grams are still being treated as words downstream.
            isSentence = hanzi.length > Segmenter.MAX_WORD_LENGTH,
        )
    }

    /** MOVIE cards teach one character and carry its keyword and reading, each wrapped in HTML. */
    private fun parseCharacter(title: String, fields: List<String>): ParsedCard {
        val hanzi = ChineseText.hanRuns(title).firstOrNull() ?: return ParsedCard.EMPTY
        return ParsedCard(
            hanzi = hanzi,
            pinyin = alignedReading(hanzi, soleReading(fields)),
            english = null,
            isSentence = false,
        )
    }

    /**
     * The one string on the card that reads as pinyin, or nothing.
     *
     * Insisting on exactly one is the whole safeguard: mnemonic bodies quote readings too, and a
     * second candidate means we cannot tell which is the card's own — so we take neither and let
     * the dictionary answer instead.
     */
    private fun soleReading(fields: List<String>): String? =
        fields.filter(ChineseText::isPinyin).distinct().singleOrNull()

    /**
     * [reading], but only if it actually belongs to [text].
     *
     * A mismatch means the string was misidentified, or that a cloze covered only part of the word
     * — either way, no reading beats a wrong one. Stored normalised so the same check can be redone
     * later against the stored characters, without the card.
     */
    private fun alignedReading(text: String, reading: String?): String? {
        // The pinyin test matters most on the cloze path, which is the one that does not go through
        // soleReading: a cloze blanks whatever the card author chose, and on some cards that is the
        // characters themselves ({{c1::吃}}{{c2::饭}}) or the English. Counting alone would accept
        // either, because Han characters are letters too.
        if (reading == null || !ChineseText.isPinyin(reading)) return null
        return ChineseText.alignReadings(text, reading)
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
    }

    private enum class Kind { SENTENCE, CLOZE, CHARACTER, GENERIC }

    /**
     * Matched on substrings because Traverse prefixes templates with the course
     * (`/Mandarin_Blueprint/MSLK Card`) in some places and not others.
     *
     * PROP and WORD CONNECTION fall through to [Kind.GENERIC] deliberately: they carry a bare word
     * and nothing else, so the generic scan already reads them correctly and there is no reading or
     * gloss to recover. ACTOR and SET never reach here — they are excluded before the fetch.
     */
    private fun kindOf(template: String): Kind = when {
        template.contains("MSLK") -> Kind.SENTENCE
        template.contains("Cloze", ignoreCase = true) -> Kind.CLOZE
        template.contains("MOVIE") -> Kind.CHARACTER
        else -> Kind.GENERIC
    }

    private const val CLOZE_MARKER = "{{c"
    private val CLOZE = Regex("\\{\\{c\\d+::(.*?)}}")
}
