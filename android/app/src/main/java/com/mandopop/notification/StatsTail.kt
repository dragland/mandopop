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
     * One progress number on purpose: coverage (SUBTLEX mass of `known_words` / corpus mass).
     * A stat whose denominator needs a footnote has no place on an ambient surface. Minutes is
     * effort, an orthogonal axis. Zipf-front-loaded: token coverage, never comprehension.
     */
    suspend fun refresh(context: Context) {
        // Reuse a fresh line: the UsageStats scan covers since-midnight and grows all day.
        stored?.let {
            if (sameLocalDay(it.second) && System.currentTimeMillis() - it.second < FRESH_MS) return
        }
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
            // One decimal: coverage moves ~0.1%/word. 认识 not 看得懂 — recognition is what
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
