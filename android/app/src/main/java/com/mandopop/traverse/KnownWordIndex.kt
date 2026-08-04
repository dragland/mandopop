package com.mandopop.traverse

import android.util.Log
import com.mandopop.data.CardContentDao
import com.mandopop.data.CardContentEntity
import com.mandopop.data.KnownWordDao
import com.mandopop.data.KnownWordEntity
import com.mandopop.dictionary.CedictEntry
import com.mandopop.dictionary.DictionaryRepository

/**
 * Turns cached card content into the answer to "does this person know a Chinese word for X?".
 *
 * This is the structure the immersion features are for — annotation, progressive replacement,
 * coverage, what-to-learn-next all reduce to a membership test over this table. Consumers go
 * English-first: ask CC-CEDICT for the candidates, then keep the ones in here. No reverse index is
 * needed, because the dictionary already maps English to entries and this side is a few hundred
 * strings.
 *
 * Everything here is derived and thrown away on every rebuild. That is the point: the derivation
 * rules are the part most likely to be wrong, and being wrong must cost a local recompute rather
 * than ~940 document reads against Traverse's project.
 */
class KnownWordIndex(
    private val dictionary: DictionaryRepository,
    private val cardContentDao: CardContentDao,
    private val knownWordDao: KnownWordDao,
) {

    /**
     * Rebuilds the whole table from `card_content`, and returns how many words it holds.
     *
     * Wholesale rather than incremental so that words can *leave*: suspending a lesson should
     * retract its vocabulary, and a diff would only ever add.
     */
    suspend fun rebuild(): Int {
        val cards = cardContentDao.startedCardsWithContent()
        if (cards.isEmpty()) {
            knownWordDao.replaceAll(emptyList())
            return 0
        }

        val candidates = cards.flatMapTo(mutableSetOf()) { Segmenter.candidates(it.hanzi.orEmpty()) }
        val vocabulary = dictionary.knownSimplified(candidates)
        // A dictionary that answers "none of these are words" is indistinguishable from one that
        // failed to open, and the difference is not academic: with no membership every sentence
        // segments into bare characters, every two-character word disappears, and `replaceAll`
        // would overwrite a correct index with that. Refuse rather than publish it.
        if (candidates.isNotEmpty() && vocabulary.isEmpty()) {
            throw TraverseException("Dictionary unavailable — refusing to rebuild the word index")
        }
        val isWord: (String) -> Boolean = vocabulary::contains

        // A card that resolves to exactly one word teaches that word; anything that splits was met
        // in passing. Sentences are laid down first so a direct card always wins the overlap.
        val cut = cards.map { it to Segmenter.segment(it.hanzi.orEmpty(), isWord) }
        val words = linkedMapOf<String, Draft>()
        for ((card, segments) in cut.sortedBy { (_, segments) -> if (segments.size == 1) 1 else 0 }) {
            collect(card, segments, words)
        }

        // One batched query rather than one per word, for the same reason as the membership pass:
        // several hundred point lookups is round-trip cost with nothing to show for it.
        val entries = dictionary.entriesBySimplified(words.keys, limitPerWord = MAX_READINGS)
        val rows = words.values.map { draft ->
            val entry = preferredEntry(entries[draft.hanzi].orEmpty(), draft.pinyin)
            KnownWordEntity(
                hanzi = draft.hanzi,
                pinyin = draft.pinyin ?: entry?.pinyin,
                english = entry?.definitions?.take(MAX_SENSES)?.joinToString("; "),
                source = draft.source,
            )
        }
        knownWordDao.replaceAll(rows)
        Log.i(
            TAG,
            "Known words: ${rows.size} (${rows.count { it.source == TAUGHT }} taught, " +
                "${rows.count { it.english != null }} glossed)",
        )
        return rows.size
    }

    /**
     * Folds one card's words into [into].
     *
     * A word inside a sentence takes the syllables sitting over its own characters — the card's
     * reading, not a dictionary guess — which works because [ChineseText.alignReadings] re-checks
     * the reading against the characters before handing out any of it.
     */
    private fun collect(
        card: CardContentEntity,
        segments: List<Segment>,
        into: MutableMap<String, Draft>,
    ) {
        if (segments.isEmpty()) return
        val text = card.hanzi ?: return
        val source = if (segments.size == 1) TAUGHT else SENTENCE
        val readings = ChineseText.alignReadings(text, card.pinyin)

        for (segment in segments) {
            val reading = Segmenter.readingFor(segment, readings)
            into[segment.text] = Draft(
                hanzi = segment.text,
                pinyin = reading ?: into[segment.text]?.pinyin,
                source = source,
            )
        }
    }

    private data class Draft(val hanzi: String, val pinyin: String?, val source: String)

    companion object {
        private const val TAG = "KnownWordIndex"
        private const val TAUGHT = KnownWordEntity.SOURCE_TAUGHT
        private const val SENTENCE = KnownWordEntity.SOURCE_SENTENCE
        private const val MAX_SENSES = 2

        /**
         * Deep enough to see past the surnames. 花 has four rows and the common sense is second;
         * a tighter cap would cut the very entry the tiebreak below is looking for.
         */
        private const val MAX_READINGS = 6

        /**
         * The dictionary entry a word most likely means, given the reading its card gave.
         *
         * Reverse lookup is ordered by row id and CC-CEDICT lists capitalised surnames first, so 花
         * is `Huā`, 马 is `Mǎ` and 年 is `Nián` unless something breaks the tie. Case is the whole
         * signal: those surnames share their tone-marked spelling with the common word, so matching
         * case-insensitively picks the surname just as reliably as not matching at all. Hence the
         * order — exact spelling, then a tone match that is *not* capitalised, then give up.
         *
         * This only rescues words a card supplied a reading for. The ordering defect itself belongs
         * in the dictionary build, where both platforms would inherit the fix.
         */
        internal fun preferredEntry(entries: List<CedictEntry>, reading: String?): CedictEntry? {
            if (entries.isEmpty()) return null
            if (reading == null) return entries.first()
            val wanted = squash(reading)
            return entries.firstOrNull { squash(it.pinyin) == wanted }
                ?: entries.firstOrNull {
                    squash(it.pinyin).equals(wanted, ignoreCase = true) &&
                        !it.pinyin.first().isUpperCase()
                }
                ?: entries.firstOrNull { squash(it.pinyin).equals(wanted, ignoreCase = true) }
                ?: entries.first()
        }

        private fun squash(pinyin: String) = pinyin.filterNot { it.isWhitespace() }
    }
}
