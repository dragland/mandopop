package com.mandopop.notification

import android.content.Context
import com.mandopop.data.MandopopDatabase
import com.mandopop.dictionary.DictionaryRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The expanded view's stats line (spec.md §4.3), cached in memory and recomputed at the moments
 * the notification is actually rebuilt — sync, app resume, shade-pull. Day-gated like the
 * briefing: yesterday's "min studied" must not survive midnight. Nothing persists; the mirror
 * and UsageStats are re-queried each time.
 */
object StatsTail {

    @Volatile
    private var stored: Pair<String, Long>? = null


    val line: String?
        get() = stored?.takeIf { sameLocalDay(it.second) }?.first

    /**
     * One progress number, on purpose. Coverage — SUBTLEX mass of `known_words` over the whole
     * corpus — is the single tracked truth; a second memory-decay count was built, audited by
     * its owner down from 1150 to 264, and then deleted, because a stat whose denominator
     * needs a footnote is a bad ambient stat. Minutes is today's effort, a different axis.
     * Coverage is front-loaded by Zipf (this account's top-10 words carry 26 of its 51
     * points) — token coverage, never comprehension.
     */
    suspend fun refresh(context: Context) {
        val database = MandopopDatabase.get(context)
        val known = database.frontierDao().knownHanzi()
        if (known.isEmpty()) {
            stored = null
            return
        }
        val dictionary = DictionaryRepository.shared(context)
        val (knownMass, totalMass) = dictionary.frequencyCoverage(known)
        if (totalMass <= 0) {
            stored = null
            return
        }
        val line = buildString {
            // One decimal on purpose: coverage moves ~0.1% per learned word, and integer
            // rounding would hide a week of progress. 认识, not 看得懂 — recognition is what
            // token coverage measures.
            append("日常中文 ≈%.1f%% 认识".format(knownMass / totalMass * 100.0))
            UsageMinutes.today(context)?.takeIf { it > 0 }?.let {
                append(" · 今天学了 $it 分钟")
            }
        }
        stored = line to System.currentTimeMillis()
    }

    private const val FRESH_MS = 60_000L

    private fun sameLocalDay(thenMs: Long): Boolean {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate() == LocalDate.now(zone)
    }
}
