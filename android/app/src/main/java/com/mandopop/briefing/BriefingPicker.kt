package com.mandopop.briefing

import com.mandopop.data.FrontierWord
import java.time.Instant
import java.time.ZoneId

/**
 * The deterministic pre-step of the briefing pipeline: *code picks, model composes, verifier
 * enforces* (spec.md §5).
 *
 * This is the "code picks" part. It chooses the salient item of the user's day, extracts its
 * content words in code, maps them into vocabulary the user actually has, and selects at most one
 * frontier word. The model sees only a short gist — one field-extracted, hard-capped title, never
 * a wall of notification text — plus the chosen Chinese words, which is the prompt-size
 * discipline a 2B-class model needs and shrinks the injection surface. It does not eliminate it:
 * a push *title* is still attacker-authored prose inside the prompt, and the actual bound on a
 * hostile push is the verifier — nothing outside known vocabulary renders, whatever the model
 * was talked into. Do not relax the verifier on the theory that prompts are clean.
 *
 * Pure JVM: Android never appears, the dictionary arrives as a function, so the whole selection
 * policy is unit-testable.
 */
object BriefingPicker {

    enum class SourceKind { CALENDAR, NOTIFICATION, SCREEN }

    data class Plan(
        val kind: SourceKind,
        /** English topic line for the model prompt. Short, field-extracted, never a raw wall. */
        val gist: String,
        /** Chinese words the sentence should be built from — all known, plus the frontier word. */
        val words: List<String>,
        /** At most one not-yet-taught word the sentence may introduce — unglossed:
         *  noticing without the answer. */
        val frontier: FrontierWord?,
        /** Structured slots for the no-model template fallback. */
        val timeOfDay: String?,
        val topic: String?,
    )

    suspend fun plan(
        inputs: BriefingInputs,
        known: Set<String>,
        frontier: List<FrontierWord>,
        zone: ZoneId,
        lookup: suspend (String) -> String?,
    ): Plan? {
        val frontierByGlossWord = glossIndex(frontier)

        // Pass 1 — sources that can actually say something in the user's vocabulary. Every
        // event and every notification gets a chance: locking onto the first calendar event
        // regardless handed the model a gist that was one untranslatable name ("Alexander …
        // vacation") while a perfectly expressible notification sat right behind it.
        for (event in inputs.events) {
            buildPlan(
                kind = SourceKind.CALENDAR,
                gist = calendarGist(event, zone),
                text = event.title,
                timeOfDay = timeOfDay(event, zone, known),
                known = known,
                frontierByGlossWord = frontierByGlossWord,
                lookup = lookup,
                viableWithoutTopic = false,
            )?.let { return it }
        }
        for (notification in rankedNotifications(inputs.notifications)) {
            buildPlan(
                kind = SourceKind.NOTIFICATION,
                gist = notificationGist(notification),
                text = listOf(notification.title, notification.text)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                timeOfDay = null,
                known = known,
                frontierByGlossWord = frontierByGlossWord,
                lookup = lookup,
                viableWithoutTopic = false,
            )?.let { return it }
        }
        inputs.screen?.let { screen ->
            buildPlan(
                kind = SourceKind.SCREEN,
                gist = "what the user is reading on their phone right now",
                text = screen.text,
                timeOfDay = null,
                known = known,
                frontierByGlossWord = frontierByGlossWord,
                lookup = lookup,
                viableWithoutTopic = false,
            )?.let { return it }
        }
        // Pass 2 — nothing anywhere maps. A calendar event is still briefable as pure time
        // ("you have plans this afternoon"): true, relevant, comprehensible, boring.
        inputs.events.firstOrNull()?.let { event ->
            buildPlan(
                kind = SourceKind.CALENDAR,
                gist = calendarGist(event, zone),
                text = event.title,
                timeOfDay = timeOfDay(event, zone, known),
                known = known,
                frontierByGlossWord = frontierByGlossWord,
                lookup = lookup,
                viableWithoutTopic = true,
            )?.let { return it }
        }
        return null
    }

    private suspend fun buildPlan(
        kind: SourceKind,
        gist: String,
        text: String,
        timeOfDay: String?,
        known: Set<String>,
        frontierByGlossWord: Map<String, FrontierWord>,
        lookup: suspend (String) -> String?,
        viableWithoutTopic: Boolean,
    ): Plan? {
        val knownMatches = mutableListOf<String>()
        var frontierMatch: FrontierWord? = null

        for (word in contentWords(text)) {
            val hanzi = lookup(word)
            if (hanzi != null && hanzi in known) {
                if (hanzi !in knownMatches) knownMatches += hanzi
            } else if (frontierMatch == null) {
                // The one un-taught word the sentence may carry (unglossed): a word the course
                // will teach anyway, matched by the dictionary's pick or the card's own gloss.
                frontierMatch = frontierByGlossWord[word]
                    ?: hanzi?.let { h -> frontierByGlossWord.values.firstOrNull { it.hanzi == h } }
            }
            if (knownMatches.size >= MAX_TOPIC_WORDS && frontierMatch != null) break
        }

        if (!viableWithoutTopic && knownMatches.isEmpty() && frontierMatch == null) return null

        val timeWords = listOfNotNull(
            TODAY.takeIf { it in known },
            timeOfDay,
        )
        val words = (timeWords + knownMatches.take(MAX_TOPIC_WORDS) +
            listOfNotNull(frontierMatch?.hanzi)).distinct().take(MAX_PROMPT_WORDS)
        if (words.isEmpty()) return null

        return Plan(
            kind = kind,
            gist = gist,
            words = words,
            frontier = frontierMatch,
            timeOfDay = timeOfDay,
            topic = knownMatches.firstOrNull { it.length in 2..4 }
                ?: frontierMatch?.hanzi
                ?: knownMatches.firstOrNull(),
        )
    }

    /**
     * People-first, then recency: a message beats a promo, and among equals the newest wins.
     * Rank, don't filter — a shade with only promos still describes the user's day.
     */
    private fun rankedNotifications(notifications: List<ActiveNotification>): List<ActiveNotification> =
        notifications.sortedWith(
            compareByDescending<ActiveNotification> { it.category in PERSONAL_CATEGORIES }
                .thenByDescending { it.postTimeMs },
        )

    private fun calendarGist(event: CalendarEvent, zone: ZoneId): String {
        val title = event.title.take(MAX_GIST_CHARS)
        if (event.allDay) return "today's calendar event: \"$title\""
        val time = Instant.ofEpochMilli(event.beginMs).atZone(zone)
        return "today's calendar event: \"$title\" at %d:%02d".format(time.hour, time.minute)
    }

    private fun notificationGist(notification: ActiveNotification): String {
        val subject = notification.title.ifBlank { notification.text }.take(MAX_GIST_CHARS)
        return "a pending notification from ${notification.appLabel}: \"$subject\""
    }

    /** The time word for the sentence — only offered when the user has actually learned it. */
    private fun timeOfDay(event: CalendarEvent, zone: ZoneId, known: Set<String>): String? {
        if (event.allDay) return null
        val hour = Instant.ofEpochMilli(event.beginMs).atZone(zone).hour
        val word = when {
            hour < 6 -> return null
            hour < 12 -> MORNING
            hour < 18 -> AFTERNOON
            else -> EVENING
        }
        return word.takeIf { it in known }
    }

    /**
     * English content words by descending frequency, stopwords and short words dropped. No POS
     * tagging — the reverse dictionary is the arbiter of whether a token means anything.
     */
    fun contentWords(text: String, limit: Int = MAX_CONTENT_WORDS): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (match in WORD.findAll(text)) {
            val word = match.value.lowercase()
            if (word.length < 3 || word in STOPWORDS) continue
            counts[word] = (counts[word] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }.take(limit)
    }

    private fun glossIndex(frontier: List<FrontierWord>): Map<String, FrontierWord> {
        val index = mutableMapOf<String, FrontierWord>()
        for (word in frontier) {
            val gloss = word.english ?: continue
            for (match in WORD.findAll(gloss)) {
                val key = match.value.lowercase()
                if (key.length < 3 || key in STOPWORDS) continue
                index.putIfAbsent(key, word)
            }
        }
        return index
    }

    private const val TODAY = "今天"
    private const val MORNING = "上午"
    private const val AFTERNOON = "下午"
    private const val EVENING = "晚上"

    /** Hard cap on any untrusted text entering a prompt — one short line, never a wall. */
    private const val MAX_GIST_CHARS = 80

    private const val MAX_CONTENT_WORDS = 8
    private const val MAX_TOPIC_WORDS = 3
    private const val MAX_PROMPT_WORDS = 6

    private val WORD = Regex("[A-Za-z][A-Za-z']+")

    private val PERSONAL_CATEGORIES = setOf("msg", "email", "call", "missed_call")

    private val STOPWORDS = setOf(
        "the", "and", "for", "you", "your", "with", "from", "that", "this", "have", "has",
        "are", "was", "were", "will", "would", "can", "could", "should", "not", "but", "all",
        "any", "our", "out", "get", "got", "new", "now", "just", "about", "into", "over",
        "than", "then", "them", "they", "there", "their", "what", "when", "where", "who",
        "how", "why", "yes", "off", "one", "two", "via", "per", "his", "her", "him", "she",
        "its", "it's", "been", "being", "more", "most", "some", "such", "only", "also",
        "here", "very", "let", "lets", "please", "hey", "did", "does", "don't", "didn't",
    )
}
