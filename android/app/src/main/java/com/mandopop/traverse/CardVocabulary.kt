package com.mandopop.traverse

import android.util.Log
import com.mandopop.data.CardContentDao
import com.mandopop.data.CardContentEntity
import com.mandopop.dictionary.CedictEntry
import com.mandopop.dictionary.DictionaryRepository

/**
 * Recovers the word each Traverse card teaches, and caches it.
 *
 * Most cards identify themselves by an opaque id rather than by hanzi, so the characters have to
 * come from the card document and then be confirmed against the dictionary — CC-CEDICT membership
 * is what separates a real word from a mnemonic prop.
 */
class CardVocabulary(
    private val firestore: FirestoreRest,
    private val dictionary: DictionaryRepository,
    private val dao: CardContentDao,
) {

    /**
     * Resolves a bounded batch of due cards.
     *
     * Fetching the whole deck at once would stall a sync and spend ~900 reads on Traverse's
     * project, so this trickles: only cards that are due, only ones never looked at, capped per
     * run. Every outcome is cached — including "no word here" — so nothing is fetched twice.
     */
    suspend fun backfill(author: String, boundaryMs: Long, batchSize: Int) {
        dao.deleteSoundOnlyCards().let {
            if (it > 0) Log.i(TAG, "Dropped $it pinyin-card words scraped from mnemonics")
        }

        val pending = dao.dueCardsMissingContent(boundaryMs, batchSize)
        if (pending.isEmpty()) return

        val now = System.currentTimeMillis()
        val resolved = mutableListOf<CardContentEntity>()
        for (cardId in pending) {
            val doc = try {
                firestore.card(author, cardId)
            } catch (error: TraverseException) {
                Log.w(TAG, "Card content fetch failed for $cardId: ${error.message}")
                break // Likely auth or rules; stop the batch rather than burn the rest on failures.
            }
            resolved += resolve(cardId, doc, now)
        }

        if (resolved.isNotEmpty()) {
            dao.putAll(resolved)
            Log.i(TAG, "Resolved ${resolved.count { it.english != null }}/${resolved.size} cards")
        }
    }

    private suspend fun resolve(cardId: String, doc: CardDoc?, now: Long): CardContentEntity {
        // A hanzi-named card is its own answer, so the id is offered as a candidate too.
        val candidates = HanziExtractor.candidates(
            primary = doc?.title ?: cardId,
            others = doc?.strings.orEmpty() + cardId,
        )

        var entity = CardContentEntity(cardId, null, null, null, now)
        for (candidate in candidates) {
            val entries = dictionary.lookupBySimplified(candidate, limit = MAX_READINGS)
            if (entries.isNotEmpty()) {
                return CardContentEntity(
                    cardId = cardId,
                    hanzi = entries.first().simplified,
                    pinyin = entries.first().pinyin,
                    english = formatGloss(entries),
                    fetchedAtMs = now,
                )
            }
            // Remember the characters even when the dictionary does not know them, so the card is
            // not refetched and a later dictionary update can still resolve it.
            if (entity.hanzi == null) entity = entity.copy(hanzi = candidate)
        }
        return entity
    }

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
    }
}
