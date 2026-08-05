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

        // Every word the deck shows, with the readings it gave them. Order is irrelevant: a word
        // taught outright on any card is taught, whichever card is folded in first.
        val words = linkedMapOf<String, Draft>()
        for (card in cards.sortedBy { it.cardId }) {
            collect(card, Segmenter.segment(card.hanzi.orEmpty(), isWord), words)
        }

        // One batched query rather than one per word, for the same reason as the membership pass:
        // several hundred point lookups is round-trip cost with nothing to show for it.
        val entries = dictionary.entriesBySimplified(words.keys, limitPerWord = MAX_READINGS)
        val rows = words.values.mapNotNull { draft ->
            val reading = draft.reading()
            val entry = preferredEntry(entries[draft.hanzi].orEmpty(), reading)
                ?: return@mapNotNull null
            KnownWordEntity(
                hanzi = draft.hanzi,
                // Where the two agree apart from case, take the dictionary's spelling. Where they
                // genuinely differ the card wins — it carries sandhi that citation forms do not —
                // but the capital still follows the dictionary, which is the thing that knows
                // whether this is a proper noun. So 一 opening a sentence is stored `yì`, and
                // 周日 stays `Zhōu rì` because CC-CEDICT capitalises Sunday.
                pinyin = canonicalise(reading, entry.pinyin),
                english = entry.definitions.take(MAX_SENSES).joinToString("; "),
                source = draft.source,
            )
        }
        knownWordDao.replaceAll(rows)
        Log.i(
            TAG,
            "Known words: ${rows.size} (${rows.count { it.source == TAUGHT }} taught)",
        )
        return rows.size
    }

    /**
     * Folds one card's words into [into].
     *
     * A word inside a sentence takes the syllables sitting over its own characters — the card's
     * reading, not a dictionary guess — which works because [Pinyin.align] re-checks the reading
     * against the characters before handing out any of it.
     *
     * A card *teaches* its words when every Han run on it is exactly one word. That covers the
     * vocabulary cards listing two or three side by side (`妈 爸`, `哪儿 那儿 这儿`), which the old
     * one-segment rule mislabelled as things merely met in passing — 28 of them.
     */
    private fun collect(
        card: CardContentEntity,
        segments: List<Segment>,
        into: MutableMap<String, Draft>,
    ) {
        val text = card.hanzi ?: return
        if (segments.isEmpty()) return
        val taught = segments.size == ChineseText.hanRuns(text).size
        val readings = Pinyin.align(text, card.pinyin)

        for (segment in segments) {
            into.getOrPut(segment.text) { Draft(segment.text) }
                .add(Segmenter.readingFor(segment, readings), taught)
        }
    }

    /**
     * A word, every reading the deck gave it, and whether any card taught it outright.
     *
     * Readings are counted rather than overwritten: 个 is `ge` on thirty cards and `gè` on three,
     * and taking whichever arrived last made the stored value depend on SQLite's row order — one of
     * twelve rows that changed between runs for no reason at all.
     */
    private class Draft(val hanzi: String) {
        private val readings = mutableMapOf<String, Int>()
        var source: String = SENTENCE
            private set

        fun add(reading: String?, taught: Boolean) {
            if (reading != null) readings[reading] = (readings[reading] ?: 0) + 1
            if (taught) source = TAUGHT
        }

        /** The deck's majority reading, ties broken by spelling so the answer is stable. */
        fun reading(): String? = readings.entries
            .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
    }

    companion object {
        private const val TAG = "KnownWordIndex"
        private const val TAUGHT = KnownWordEntity.SOURCE_TAUGHT
        private const val SENTENCE = KnownWordEntity.SOURCE_SENTENCE

        /**
         * Deep enough to reach the sense the deck teaches.
         *
         * At two, 号 read "to call out; bugle" instead of "day of the month" on 25 cards and 热
         * read "to warm up" instead of "hot" on 11 — the right entry every time, truncated above
         * the meaning. CC-CEDICT orders senses by neither frequency nor register, so there is no
         * cleverer rule available than showing more of them.
         */
        private const val MAX_SENSES = 4

        /** Deep enough to see past the surname and cross-reference rows, which sort first. */
        private const val MAX_READINGS = 6

        /** `old variant of 和[he2]`, `see 他媽的` — a pointer to another entry, not a meaning. */
        private val CROSS_REFERENCE = Regex("^(old |archaic )?variant of |^see (also )?\\S")

        /** CC-CEDICT carries rows for Kangxi radicals and strokes; nobody "knows" those as words. */
        private val COMPONENT = Regex("(radical|component|stroke) \\(?(in|of) Chinese characters")

        /**
         * The dictionary entry a word most likely means, given the reading its card gave.
         *
         * Three things fight the caller here, all of them CC-CEDICT's row order:
         *
         * *Cross-references sort first.* 和 leads with "old variant of 和[he2]" at the very reading
         * the deck uses, so the commonest word in the deck was glossed as a pointer to itself — on
         * 45 cards. Skipped whenever a real definition exists at the same reading.
         *
         * *Surnames sort first.* 花 is `Huā` before `huā`, 马 is `Mǎ` before `mǎ`. A card writes its
         * reading capitalised whenever the word opens a sentence, so case cannot settle it and the
         * surname wins on an exact match. CC-CEDICT labels these, so the label decides instead —
         * which also means 周日 keeps its capitalised `Zhōu rì` "Sunday" rather than being pushed
         * onto "(dialect) weekday". The cost is 张, taught as the surname Zhang on one card and now
         * read as "to open up".
         *
         * *And nothing sorts by usefulness.* Where the reading matches nothing at all — a typo on
         * the card, `tā yé hěn màn` for `yě` — falling straight to the first row reintroduced the
         * surname bias this exists to remove, so the same preferences apply to the fallback.
         */
        internal fun preferredEntry(entries: List<CedictEntry>, reading: String?): CedictEntry? {
            val usable = entries.filterNot { it.definitions.firstOrNull()?.let(::isComponent) ?: true }
            if (usable.isEmpty()) return null
            val wanted = reading?.let(::squash)
            val sameReading = usable.filter { squash(it.pinyin).equals(wanted, ignoreCase = true) }
            val candidates = sameReading.ifEmpty { usable }

            // First plain entry in CC-CEDICT's own order, and nothing else. Preferring an exact
            // case match ahead of it re-broke 周日: mid-sentence cards write `zhōu rì`, which
            // matches "(dialect) weekday" exactly while "Sunday" is capitalised. And falling back
            // to the first row of *any* kind kept 丩, whose only entry points at another word.
            return candidates.firstOrNull { it.isPlain() }
        }

        /** The card's reading, spelled and capitalised as the dictionary would. */
        private fun canonicalise(reading: String?, entry: String): String {
            if (reading == null || reading.equals(entry, ignoreCase = true)) return entry
            val proper = entry.firstOrNull()?.isUpperCase() ?: false
            return if (proper) reading else reading.replaceFirstChar(Char::lowercaseChar)
        }

        /** Neither a pointer to another entry nor a proper noun. */
        private fun CedictEntry.isPlain(): Boolean {
            val first = definitions.firstOrNull() ?: return false
            return !CROSS_REFERENCE.containsMatchIn(first) && !first.startsWith("surname ")
        }

        private fun isComponent(definition: String) = COMPONENT.containsMatchIn(definition)

        private fun squash(pinyin: String) = pinyin.filterNot { it.isWhitespace() }
    }
}
