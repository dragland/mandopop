package com.mandopop.traverse

import android.util.Log
import com.mandopop.data.CardContentDao
import com.mandopop.data.CardContentEntity
import com.mandopop.data.PendingCard
import com.mandopop.dictionary.CedictEntry
import com.mandopop.dictionary.DictionaryRepository
import kotlinx.coroutines.delay

/**
 * Recovers what each Traverse card teaches, and caches it.
 *
 * Cards identify themselves by an opaque id rather than by hanzi, so the content has to come from
 * the card document, where [CardParser] reads it by field name. CC-CEDICT then decides whether what
 * came back is a headword or an utterance.
 */
class CardVocabulary(
    private val firestore: FirestoreRest,
    private val dictionary: DictionaryRepository,
    private val dao: CardContentDao,
) {

    /**
     * The result of a drain: whether anything was written, and what went wrong if anything did.
     *
     * The failure is returned rather than thrown so the caller can still derive the word index from
     * whatever *was* read before reporting it. Throwing from inside the drain skipped that, and a
     * one-shot guard that also suppresses the rebuild leaves the index stale with nothing to
     * retrigger it.
     */
    data class Backfill(val changed: Boolean, val failure: TraverseException? = null)

    /**
     * Brings the content cache up to date.
     *
     * The trigger is an invariant — every card that is not a pinyin drill should have a content row
     * at the current [CardParser.VERSION] — rather than a job, so it satisfies itself on install,
     * on sign-in, when a lesson unlocks and after a parser fix, with no first-run flag to forget.
     *
     * [limit] is a runaway guard, not pacing: cached negatives already stop anything being fetched
     * twice, and the whole deck drains in a handful of batched requests.
     */
    suspend fun backfill(limit: Int): Backfill {
        dao.deleteSoundOnlyCards().let {
            if (it > 0) Log.i(TAG, "Dropped $it pinyin-card words scraped from mnemonics")
        }

        val pending = dao.cardsNeedingContent(CardParser.VERSION, limit)
        if (pending.isEmpty()) return Backfill(changed = false)

        Log.i(TAG, "Fetching content for ${pending.size} cards")
        val outcomes = mutableListOf<Outcome>()
        var changed = false
        // Persisted a chunk at a time, so a worker cut short mid-drain keeps what it read and the
        // invariant simply asks for the rest next run. Grouped by author first, because a batchGet
        // addresses one collection.
        val chunks = pending.groupBy { it.authorUserName }
            .flatMap { (author, cards) ->
                cards.chunked(FirestoreRest.CARD_BATCH_SIZE).map { author to it }
            }

        for ((index, batch) in chunks.withIndex()) {
            val (author, chunk) = batch
            if (index > 0) delay(FirestoreRest.CARD_BATCH_PAUSE_MS)
            val documents = firestore.cards(author, chunk.map { it.cardId })

            // Checked before anything is written. A systemic zero-document response — a renamed
            // collection, a wrong author — is neither a real 404 nor a parse failure, and caching
            // it at the current version would mark the whole deck permanently answered.
            val missing = chunk.count { documents[it.cardId] == null }
            if (missing * 2 > chunk.size && chunk.size >= MIN_SAMPLE) {
                return Backfill(
                    changed = changed,
                    failure = TraverseException(
                        "Traverse returned no document for $missing of ${chunk.size} cards",
                    ),
                )
            }

            val now = System.currentTimeMillis()
            val resolved = chunk.map { card -> resolve(card, documents[card.cardId], now) }
            dao.putAll(resolved)
            changed = true
            chunk.forEachIndexed { position, card ->
                outcomes += Outcome(card.template, resolved[position].hanzi != null)
            }
        }
        return Backfill(changed = changed, failure = report(outcomes))
    }

    /**
     * Reads one card, falling back to the dictionary for anything it did not state.
     *
     * There is deliberately no generic fallback for a template with no rule. Scanning an unknown
     * card for Han and letting CC-CEDICT arbitrate produced *something* for every card, which is
     * the problem: an unmapped template would quietly contribute mnemonic props as vocabulary
     * instead of showing up as unreadable. An empty row is the louder answer, and the coverage
     * readout names it.
     */
    private suspend fun resolve(card: PendingCard, doc: CardDoc?, now: Long): CardContentEntity {
        val blank = CardContentEntity(
            cardId = card.cardId,
            hanzi = null,
            pinyin = null,
            english = null,
            fetchedAtMs = now,
            parserVersion = CardParser.VERSION,
        )
        val parsed = CardParser.parse(card.template, doc)
        if (parsed.hanzi == null) return blank

        // Whether this is a headword or an utterance is decided by CC-CEDICT, not by length or by
        // template. Both heuristics were wrong in opposite directions: MSLK was marked "always a
        // sentence" and 知道 is a word, while 他很快吗 is four characters and is not. Dictionary
        // membership is the property that actually matters downstream — it is exactly what decides
        // whether the notification can prompt with it and Reveal can look it up.
        val entries = dictionary.lookupBySimplified(parsed.hanzi, limit = MAX_READINGS)
        return CardContentEntity(
            cardId = card.cardId,
            hanzi = parsed.hanzi,
            pinyin = parsed.pinyin ?: entries.firstOrNull()?.pinyin,
            english = parsed.english ?: entries.takeIf { it.isNotEmpty() }?.let(::formatGloss),
            fetchedAtMs = now,
            parserVersion = CardParser.VERSION,
            isSentence = entries.isEmpty(),
        )
    }

    /**
     * Logs what each template yielded, and names the one that collapsed.
     *
     * The index is the foundation for every immersion feature, so under-coverage has to be loud —
     * a quietly half-filled table would poison everything built on it and look exactly like a user
     * who has studied less. The guard only watches templates [CardParser] claims to handle, and
     * only once a batch is large enough for the rate to mean anything.
     *
     * Rows are already written by this point, deliberately: they carry the parser version that
     * produced them, so fixing the parser refetches them anyway, and discarding them would just
     * re-spend the reads. The consequence is that the error fires once per version and the durable
     * signal is the coverage readout — which is why that readout names unreadable cards outright.
     */
    private fun report(outcomes: List<Outcome>): TraverseException? {
        var failure: TraverseException? = null
        for ((template, cards) in outcomes.groupBy { it.template }) {
            val read = cards.count { it.read }
            Log.i(TAG, "$template: $read/${cards.size} read")
            if (!CardParser.handles(template)) continue
            // Reading *none* of a template is a break at any size — WORD CONNECTION has six cards
            // and would otherwise never be watched at all. Above that, only a collapse counts:
            // measured yields are 100% everywhere except PROP's 31/32, so a tighter rate would
            // fire on a handful of odd cards, and the coverage readout already names those.
            val broken = read == 0 && cards.size >= MIN_COLLAPSE ||
                cards.size >= MIN_SAMPLE && read * 2 < cards.size
            if (broken && failure == null) {
                failure = TraverseException(
                    "Could not read $template on ${cards.size - read} of ${cards.size} cards — " +
                        "card layout changed?",
                )
            }
        }
        return failure
    }

    private data class Outcome(val template: String, val read: Boolean)

    /** Re-resolved on demand, so revealing an answer needs no stored state that could go stale. */
    suspend fun glossFor(hanzi: String): String? {
        val entries = dictionary.lookupBySimplified(hanzi, limit = MAX_READINGS)
        return if (entries.isEmpty()) null else formatGloss(entries)
    }

    /**
     * Every reading, not a chosen one. 东西 is both `dōng xī` "east and west" and `dōng xi`
     * "thing"; nothing in the data says which the card means, and the gloss is only shown on
     * request, so listing both beats guessing.
     */
    private fun formatGloss(entries: List<CedictEntry>): String =
        entries.joinToString(READING_SEPARATOR) { entry ->
            "${entry.pinyin}  ${entry.definitions.take(MAX_SENSES).joinToString("; ")}"
        }

    private companion object {
        const val TAG = "CardVocabulary"
        const val MAX_READINGS = 3
        const val MAX_SENSES = 2
        const val READING_SEPARATOR = "  /  "

        /** Below this a failure *rate* is noise — a handful of genuinely odd cards, not a break. */
        const val MIN_SAMPLE = 20

        /** But reading nothing at all is a break, and needs only enough cards to not be a fluke. */
        const val MIN_COLLAPSE = 3
    }
}
