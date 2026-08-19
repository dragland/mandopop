package com.mandopop.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the queries whose silent breakage would be invisible: the due count the notification is
 * built from, and the filters that decide which cards become vocabulary. Everything else is a
 * plain unit test — this exists because that logic is SQL, and SQL needs real SQLite.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleQueriesTest {

    private lateinit var db: MandopopDatabase
    private val schedules get() = db.scheduleDao()
    private val content get() = db.cardContentDao()

    private val boundary = 2_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MandopopDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun schedule(
        id: String,
        cardId: String = id,
        template: String = "/Mandarin_Blueprint/MSLK Card",
        suspended: Boolean = false,
        dueTimeMs: Long = 1_000L,
        lapses: Int = 0,
    ) = ScheduleEntity(
        id = id,
        cardId = cardId,
        authorUserName = "Mandarin_Blueprint",
        template = template,
        topicId = "t",
        promptNr = 0,
        queue = if (suspended) "new" else "review",
        suspended = suspended,
        dueTimeMs = dueTimeMs,
        intervalDays = 1.0,
        easeFactor = 2.5,
        repetitions = 1,
        lapses = lapses,
    )

    @Test
    fun dueCountExcludesSuspendedAndCountsPromptRows() = runTest {
        schedules.replaceAll(
            listOf(
                // Same card, two prompts: two things to answer, so two rows counted.
                schedule("a-0", cardId = "个"),
                schedule("a-1", cardId = "个"),
                schedule("parked", suspended = true),
                schedule("tomorrow", dueTimeMs = boundary + 1),
            ),
        )

        assertEquals(2, schedules.countDueBefore(boundary))
        assertEquals(3, schedules.countLive())
    }

    @Test
    fun clozePoolIsStudiedSentencesContainingTheWord() = runTest {
        schedules.replaceAll(
            listOf(
                schedule("s1"),
                schedule("s2", suspended = true),
                schedule("w1"),
            ),
        )
        content.putAll(
            listOf(
                CardContentEntity("s1", "我们今天有会议。", null, "we meet", 0L, 1, isSentence = true),
                CardContentEntity("s2", "会议很重要。", null, "important", 0L, 1, isSentence = true),
                CardContentEntity("w1", "会议", "huìyì", "meeting", 0L, 1),
            ),
        )

        // Unsuspended sentences only — an unreached lesson is not fair context — and the word
        // card itself is not a sentence.
        assertEquals(listOf("我们今天有会议。"), content.sentencesContaining("会议"))
    }

    @Test
    fun frontierIsFullySuspendedNonSoundWordCards() = runTest {
        schedules.replaceAll(
            listOf(
                schedule("f1", suspended = true),
                schedule("half-0", cardId = "half", suspended = true),
                schedule("half-1", cardId = "half", suspended = false),
                schedule("actor", suspended = true, template = "/MB/ACTOR REVIEW"),
                schedule("sent", suspended = true),
            ),
        )
        content.putAll(
            listOf(
                CardContentEntity("f1", "医生", "yīshēng", "doctor", 0L, 1),
                CardContentEntity("half", "水", "shuǐ", "water", 0L, 1),
                CardContentEntity("actor", "八", "bā", "eight", 0L, 1),
                CardContentEntity("sent", "我是医生。", null, "sentence", 0L, 1, isSentence = true),
            ),
        )

        // Only the fully-suspended word card qualifies: one live prompt disqualifies (half),
        // sound-only cards are excluded per the shared predicate, sentences are not words.
        assertEquals(listOf("医生"), db.frontierDao().frontierWords().map { it.hanzi })
    }

    @Test
    fun replaceAllSwapsTheMirrorRatherThanAccumulating() = runTest {
        schedules.replaceAll(listOf(schedule("old")))
        schedules.replaceAll(listOf(schedule("new-1"), schedule("new-2")))

        assertEquals(2, schedules.count())
    }

    @Test
    fun backfillSkipsPinyinCardsThatTeachSoundsNotWords() = runTest {
        schedules.replaceAll(
            listOf(
                schedule("word", cardId = "opaque-id"),
                schedule("actor", cardId = "b-", template = "/Mandarin_Blueprint/ACTOR REVIEW"),
                schedule("set", cardId = "-ang", template = "/Mandarin_Blueprint/SET REVIEW"),
                // Radicals stay in: 一 and 十 are components *and* real words.
                schedule("prop", cardId = "一（PROP）", template = "/Mandarin_Blueprint/PROP REVIEW"),
            ),
        )

        assertEquals(
            setOf("opaque-id", "一（PROP）"),
            content.cardsNeedingContent(parserVersion = 1, limit = 10).map { it.cardId }.toSet(),
        )
        assertEquals(2, content.eligibleCardCount())
    }

    @Test
    fun backfillIgnoresCardsAlreadyResolved() = runTest {
        schedules.replaceAll(listOf(schedule("a", cardId = "c1"), schedule("b", cardId = "c2")))
        content.putAll(listOf(CardContentEntity("c1", "水", "shuǐ", "water", 0L, parserVersion = 1)))

        assertEquals(
            listOf("c2"),
            content.cardsNeedingContent(parserVersion = 1, limit = 10).map { it.cardId },
        )
    }

    @Test
    fun backfillCoversTheWholeDeckNotJustTodaysReviews() = runTest {
        // The index feeds immersion features, which need every word the user has met — not the
        // subset that happens to be due. This filter is the reason a fresh install used to take a
        // day to become useful.
        schedules.replaceAll(
            listOf(
                schedule("due", cardId = "c1"),
                schedule("later", cardId = "c2", dueTimeMs = boundary + 100_000),
                schedule("parked", cardId = "c3", suspended = true),
            ),
        )

        assertEquals(3, content.cardsNeedingContent(parserVersion = 1, limit = 10).size)
    }

    @Test
    fun aParserBumpMakesStoredRowsStaleRatherThanDone() = runTest {
        // The structural fix for cards cached as unreadable: without it, a parse failure is
        // indistinguishable from "this card genuinely has no word on it", forever.
        schedules.replaceAll(listOf(schedule("a", cardId = "c1")))
        content.putAll(listOf(CardContentEntity("c1", null, null, null, 0L, parserVersion = 1)))

        assertEquals(emptyList<String>(), content.cardsNeedingContent(1, 10).map { it.cardId })
        assertEquals(listOf("c1"), content.cardsNeedingContent(2, 10).map { it.cardId })
    }

    @Test
    fun knownWordInputCoversStartedCardsOnly() = runTest {
        schedules.replaceAll(
            listOf(
                schedule("live", cardId = "c1"),
                schedule("parked", cardId = "c2", suspended = true),
            ),
        )
        content.putAll(
            listOf(
                CardContentEntity("c1", "水", "shuǐ", "water", 0L, 1),
                // Cached because the lesson may unlock tomorrow, but not known today.
                CardContentEntity("c2", "火", "huǒ", "fire", 0L, 1),
                // Fetched and readable, but nothing on it — contributes no vocabulary.
                CardContentEntity("c3", null, null, null, 0L, 1),
            ),
        )

        assertEquals(listOf("水"), content.startedCardsWithContent().map { it.hanzi })
    }

    @Test
    fun aCardWithTwoPromptsIsOfferedAndCountedOnce() = runTest {
        // 89 of 973 cards have more than one prompt row. Without the dedupe the backfill would
        // fetch each of them twice and coverage would read over 100%.
        schedules.replaceAll(listOf(schedule("a-0", cardId = "c1"), schedule("a-1", cardId = "c1")))
        content.putAll(listOf(CardContentEntity("c1", "水", "shuǐ", "water", 0L, 1)))

        assertEquals(1, content.eligibleCardCount())
        assertEquals(1, content.fetchedCount(1))
        assertEquals(1, content.readableCount(1))
        assertEquals(1, content.startedCardsWithContent().size)
    }

    @Test
    fun aCardWithAnyPinyinPromptIsNeitherFetchedNorDeleted() = runTest {
        // The fetch filter and the cleanup delete have to agree per *card*. When they disagreed,
        // a card with one ACTOR prompt and one other was fetched by one and deleted by the other,
        // every sync, forever — against a third party's read quota.
        schedules.replaceAll(
            listOf(
                schedule("mixed-0", cardId = "c1", template = "/Mandarin_Blueprint/ACTOR REVIEW"),
                schedule("mixed-1", cardId = "c1", template = "/Mandarin_Blueprint/MSLK Card"),
            ),
        )

        assertEquals(emptyList<String>(), content.cardsNeedingContent(1, 10).map { it.cardId })
        assertEquals(0, content.eligibleCardCount())
    }

    @Test
    fun contentIsForgottenWhenItsCardLeavesTheDeck() = runTest {
        schedules.replaceAll(listOf(schedule("a", cardId = "c1"), schedule("b", cardId = "c2")))
        content.putAll(
            listOf(
                CardContentEntity("c1", "水", "shuǐ", "water", 0L, 1),
                CardContentEntity("c2", "火", "huǒ", "fire", 0L, 1),
            ),
        )
        schedules.replaceAll(listOf(schedule("a", cardId = "c1")))

        assertEquals(1, content.deleteOrphans())
        assertEquals(1, content.fetchedCount(1))
    }

    @Test
    fun soundOnlyCleanupRemovesScrapedWordsAndSparesTheRest() = runTest {
        schedules.replaceAll(
            listOf(
                schedule("actor", cardId = "-a", template = "/Mandarin_Blueprint/ACTOR REVIEW"),
                schedule("word", cardId = "c1"),
            ),
        )
        content.putAll(
            listOf(
                // 八 scraped out of a pinyin card's mnemonic — never a word the user learned.
                CardContentEntity("-a", "八", "bā", "eight", 0L),
                CardContentEntity("c1", "水", "shuǐ", "water", 0L),
            ),
        )

        assertEquals(1, content.deleteSoundOnlyCards())
        // The word card is untouched by the cleanup, which is the half that could silently overreach.
        assertEquals("水", content.dueExamples(boundary, 1).firstOrNull()?.hanzi)
    }

    @Test
    fun dueExamplePrefersTheMostForgottenResolvedCard() = runTest {
        schedules.replaceAll(
            listOf(
                schedule("easy", cardId = "c1", lapses = 0),
                schedule("hard", cardId = "c2", lapses = 7),
                schedule("late", cardId = "c3", dueTimeMs = boundary + 1, lapses = 99),
            ),
        )
        content.putAll(
            listOf(
                CardContentEntity("c1", "水", "shuǐ", "water", 0L),
                CardContentEntity("c2", "东西", "dōng xi", "thing", 0L),
                CardContentEntity("c3", "天", "tiān", "day", 0L),
            ),
        )

        assertEquals("东西", content.dueExamples(boundary, 1).firstOrNull()?.hanzi)
    }

    @Test
    fun dueExampleSkipsSentencesTheNotificationCannotPrompt() = runTest {
        // Four characters, so a length test would have let it through — and then Reveal would look
        // up a whole sentence in CC-CEDICT, find nothing, and appear to do nothing at all.
        schedules.replaceAll(
            listOf(
                schedule("sentence", cardId = "c1", lapses = 9),
                schedule("word", cardId = "c2", lapses = 0),
            ),
        )
        content.putAll(
            listOf(
                CardContentEntity("c1", "他很快吗", "tā hěn kuài ma", "Is he fast?", 0L, 1, true),
                CardContentEntity("c2", "水", "shuǐ", "water", 0L, 1),
            ),
        )

        assertEquals("水", content.dueExamples(boundary, 1).firstOrNull()?.hanzi)
    }

    @Test
    fun dueExampleSkipsCardsWithNoDictionaryMatch() = runTest {
        schedules.replaceAll(listOf(schedule("a", cardId = "c1")))
        // Characters recognised but absent from CC-CEDICT: nothing worth showing.
        content.putAll(listOf(CardContentEntity("c1", "乚", null, null, 0L)))

        assertNull(content.dueExamples(boundary, 1).firstOrNull())
    }
}
