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
            content.dueCardsMissingContent(boundary, limit = 10).toSet(),
        )
    }

    @Test
    fun backfillIgnoresCardsAlreadyResolved() = runTest {
        schedules.replaceAll(listOf(schedule("a", cardId = "c1"), schedule("b", cardId = "c2")))
        content.putAll(listOf(CardContentEntity("c1", "水", "shuǐ", "water", 0L)))

        assertEquals(listOf("c2"), content.dueCardsMissingContent(boundary, limit = 10))
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
        assertEquals(1, content.resolvedCount())
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

        assertEquals("东西", content.dueExample(boundary)?.hanzi)
    }

    @Test
    fun dueExampleSkipsCardsWithNoDictionaryMatch() = runTest {
        schedules.replaceAll(listOf(schedule("a", cardId = "c1")))
        // Characters recognised but absent from CC-CEDICT: nothing worth showing.
        content.putAll(listOf(CardContentEntity("c1", "乚", null, null, 0L)))

        assertNull(content.dueExample(boundary))
    }
}
