package com.mandopop.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Cards that teach a pinyin sound rather than a word.
 *
 * Minimal Pairs joins ACTOR and SET here: its fields are `Word 1`..`Word 3` holding `ji2`, `qi2`,
 * `xi2` — tone drills with no characters at all. Found by reading the whole course rather than the
 * part of it this account has unlocked.
 *
 * Shared by every query that has an opinion about them, because they must agree exactly: one query
 * fetching what another deletes is a loop against a third party's read quota. Matched on a suffix
 * because Traverse prefixes templates with the course in some places and not others.
 */
private const val SOUND_ONLY =
    "template LIKE '%ACTOR REVIEW' OR template LIKE '%SET REVIEW' OR template LIKE '%Minimal Pairs'"

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ScheduleEntity>)

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()

    /**
     * Replaces the whole mirror atomically. Traverse is the source of truth and a full pull is
     * ~1,000 rows, so replace-in-transaction is simpler and safer than diffing.
     */
    @Transaction
    suspend fun replaceAll(rows: List<ScheduleEntity>) {
        deleteAll()
        insertAll(rows)
    }

    @Query("SELECT COUNT(*) FROM schedules")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM schedules WHERE suspended = 0 AND due_time_ms < :boundaryMs")
    suspend fun countDueBefore(boundaryMs: Long): Int

    @Query("SELECT COUNT(*) FROM schedules WHERE suspended = 0")
    suspend fun countLive(): Int
}

/**
 * A card awaiting content: the template that decides how to read it, and the author whose
 * collection holds it.
 *
 * The author travels with the card rather than being read once for the whole deck. It is uniform
 * in practice, but the backfill now covers everything rather than today's handful, so a single
 * odd author would have 404'd — and cached as a real negative — across the entire deck at once.
 */
data class PendingCard(
    @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "template") val template: String,
    @ColumnInfo(name = "author_user_name") val authorUserName: String,
)

@Dao
interface CardContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<CardContentEntity>)

    @Query("DELETE FROM card_content")
    suspend fun deleteAll()

    /**
     * Eligible cards that have been fetched and parsed at the current version.
     *
     * Split from [readableCount] because the two shortfalls need opposite responses and are
     * otherwise indistinguishable: a card with no row yet is mid-drain and fixes itself, while a
     * card with a row and no hanzi has been read and found illegible, which is a parser bug that
     * will sit there forever. One ratio cannot say which is happening.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT s.card_id) FROM schedules s
        JOIN card_content c ON c.card_id = s.card_id AND c.parser_version >= :parserVersion
        WHERE s.card_id NOT IN (SELECT card_id FROM schedules WHERE $SOUND_ONLY)
        """,
    )
    suspend fun fetchedCount(parserVersion: Int): Int

    /** Of those, the ones that actually yielded characters. */
    @Query(
        """
        SELECT COUNT(DISTINCT s.card_id) FROM schedules s
        JOIN card_content c ON c.card_id = s.card_id AND c.parser_version >= :parserVersion
        WHERE c.hanzi IS NOT NULL
          AND s.card_id NOT IN (SELECT card_id FROM schedules WHERE $SOUND_ONLY)
        """,
    )
    suspend fun readableCount(parserVersion: Int): Int

    /**
     * Cards that should have content but do not yet — the backfill's entire trigger.
     *
     * Stated as an invariant ("every non-sound-only card has a current content row") rather than as
     * a job, so it self-satisfies on install, on sign-in, after a parser bump and when new lessons
     * unlock, with no first-run flag to forget. Deliberately *not* limited to due cards: the index
     * is for immersion features that need the whole deck, not just today's reviews.
     *
     * Exclusion is per *card*, not per row, so it agrees with [deleteSoundOnlyCards]. A card with
     * one ACTOR prompt and one other would otherwise be fetched by this query and deleted by that
     * one, on every sync, for as long as both exist.
     */
    @Query(
        """
        SELECT s.card_id AS card_id, MIN(s.template) AS template,
               MIN(s.author_user_name) AS author_user_name
        FROM schedules s
        LEFT JOIN card_content c ON c.card_id = s.card_id
        WHERE (c.card_id IS NULL OR c.parser_version < :parserVersion)
          AND s.card_id NOT IN (SELECT card_id FROM schedules WHERE $SOUND_ONLY)
        GROUP BY s.card_id
        LIMIT :limit
        """,
    )
    suspend fun cardsNeedingContent(parserVersion: Int, limit: Int): List<PendingCard>

    /** Denominator for the coverage readout: cards the backfill is expected to resolve. */
    @Query(
        """
        SELECT COUNT(DISTINCT card_id) FROM schedules
        WHERE card_id NOT IN (SELECT card_id FROM schedules WHERE $SOUND_ONLY)
        """,
    )
    suspend fun eligibleCardCount(): Int

    /**
     * Forgets content for cards that have left the deck.
     *
     * Only safe straight after a successful full pull, and only because `TraverseSync` refuses an
     * empty schedule response — otherwise one bad response would take the whole content cache with
     * it. That guard is load-bearing here.
     */
    @Query("DELETE FROM card_content WHERE card_id NOT IN (SELECT card_id FROM schedules)")
    suspend fun deleteOrphans(): Int

    /**
     * Drops words scraped from cards that teach a pinyin sound rather than a word.
     *
     * ACTOR and SET cards are keyed by an initial (`b-`) or final (`-ang`) and have no headword at
     * all, so any hanzi on them belongs to the mnemonic story — `-a` resolved to 八 and
     * `*Null* {INITIAL}` to 介. Extracting those produces vocabulary the user never learned.
     * PROP cards are deliberately *not* excluded: radicals like 一 and 十 are also real words.
     */
    @Query("DELETE FROM card_content WHERE card_id IN (SELECT card_id FROM schedules WHERE $SOUND_ONLY)")
    suspend fun deleteSoundOnlyCards(): Int

    /**
     * Every readable card the user has actually started, for the `known_words` rebuild.
     *
     * Suspended cards are excluded here rather than at fetch time: their content is worth caching
     * (the lesson may unlock tomorrow) but the word is not yet known. `DISTINCT` rather than a
     * `GROUP BY`, since the join is on the content table's primary key and a card with two prompt
     * rows would otherwise appear twice.
     */
    @Query(
        """
        SELECT DISTINCT c.* FROM card_content c
        JOIN schedules s ON s.card_id = c.card_id
        WHERE c.hanzi IS NOT NULL AND s.suspended = 0
        """,
    )
    suspend fun startedCardsWithContent(): List<CardContentEntity>

    /**
     * One resolved word that is due, preferring the most-forgotten card so it is worth showing.
     *
     * Headwords only. MSLK cards now store the whole sentence they drill, which would overrun the
     * notification's title and has no CC-CEDICT entry for the Reveal action to look up. Before
     * sentences were captured this query could pick a card whose stored "word" was really one
     * fragment scavenged out of a sentence — excluding them is the honest version of that.
     */
    /**
     * The most-forgotten due words: the notification prefers whichever of them can be shown
     * inside an i+1 sentence (a single-candidate query once locked the surface to bare-word
     * mode because 选择 has no studied sentence). Grouped per card so a multi-prompt card
     * cannot eat two candidate slots, and tie-broken by card id — the repo's own history says
     * what unordered queries do (twelve rows changed value between runs), and here a tie
     * swapping between the engine's resolution and the notifier's would silently strand the
     * generated sentence.
     */
    @Query(
        """
        SELECT c.* FROM card_content c
        JOIN schedules s ON s.card_id = c.card_id
        WHERE c.english IS NOT NULL AND c.is_sentence = 0
          AND s.suspended = 0 AND s.due_time_ms < :boundaryMs
        GROUP BY c.card_id
        ORDER BY MAX(s.lapses) DESC, MIN(s.due_time_ms) ASC, c.card_id
        LIMIT :limit
        """,
    )
    suspend fun dueExamples(boundaryMs: Long, limit: Int): List<CardContentEntity>

    /**
     * Studied sentences containing a word, for the notification's i+1 cloze. Unsuspended rows
     * only — a sentence from an unreached lesson is not fair context. The `LIKE` is the whole
     * targeting mechanism on purpose: it finds every sentence *containing* the word without the
     * `==target==` parser change (a `CardParser.VERSION` bump re-reads the deck at ~940 billed
     * documents), and the display-time i+1 filter does the quality control.
     */
    @Query(
        """
        SELECT DISTINCT c.hanzi FROM card_content c
        JOIN schedules s ON s.card_id = c.card_id
        WHERE c.is_sentence = 1 AND c.hanzi IS NOT NULL
          AND s.suspended = 0
          AND c.hanzi LIKE '%' || :word || '%'
        LIMIT 30
        """,
    )
    suspend fun sentencesContaining(word: String): List<String>
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)
}

/**
 * A word in the deck the course has not taught yet — every schedule row for its card is suspended.
 *
 * "Frontier" per spec.md §3: the course *will* teach it, so an exposure now has scheduled
 * re-encounters ahead of it. Course ordering (`Tags`/`graphInfo`) is not mirrored yet, so callers
 * rank frontier words by relevance to the moment, not by lesson order.
 */
data class FrontierWord(
    @ColumnInfo(name = "hanzi") val hanzi: String,
    @ColumnInfo(name = "pinyin") val pinyin: String?,
    @ColumnInfo(name = "english") val english: String?,
)

@Dao
interface FrontierDao {
    /**
     * Headwords whose lesson is entirely suspended. Sentences are excluded — a frontier *word* can
     * be introduced with a gloss; a whole unstudied sentence cannot. The sound-only exclusion is
     * carried here per the shared-predicate rule, not merely inherited from
     * [CardContentDao.deleteSoundOnlyCards]: sound-only cards for un-reached sounds are exactly
     * the fully-suspended cards this query selects, and a legacy mnemonic-scraped row surviving
     * until the next sync would otherwise be served as a "frontier word".
     *
     * Not filtered against `known_words` here: the same hanzi can sit on a live card and a
     * suspended one, and "frontier" must mean *un-learned* — callers subtract the known set.
     */
    @Query(
        """
        SELECT c.hanzi AS hanzi, c.pinyin AS pinyin, c.english AS english FROM card_content c
        WHERE c.hanzi IS NOT NULL AND c.is_sentence = 0
          AND EXISTS (SELECT 1 FROM schedules s WHERE s.card_id = c.card_id)
          AND NOT EXISTS (SELECT 1 FROM schedules s WHERE s.card_id = c.card_id AND s.suspended = 0)
          AND c.card_id NOT IN (SELECT card_id FROM schedules WHERE $SOUND_ONLY)
        """,
    )
    suspend fun frontierWords(): List<FrontierWord>

    /** The whole known-word vocabulary, for the briefing verifier's membership set. */
    @Query("SELECT hanzi FROM known_words")
    suspend fun knownHanzi(): List<String>

}

@Dao
interface KnownWordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<KnownWordEntity>)

    @Query("DELETE FROM known_words")
    suspend fun deleteAll()

    /**
     * Rebuilds the index wholesale. The table is derived, so replacing it is both simpler than
     * diffing and the only way a word can ever *leave* — suspending a lesson should retract it.
     */
    @Transaction
    suspend fun replaceAll(rows: List<KnownWordEntity>) {
        deleteAll()
        insertAll(rows)
    }

    @Query("SELECT COUNT(*) FROM known_words")
    suspend fun count(): Int
}

@Database(
    entities = [
        ScheduleEntity::class,
        CardContentEntity::class,
        SyncStateEntity::class,
        KnownWordEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MandopopDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun cardContentDao(): CardContentDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun knownWordDao(): KnownWordDao
    abstract fun frontierDao(): FrontierDao

    companion object {
        @Volatile
        private var instance: MandopopDatabase? = null

        /**
         * Adds the new `card_content` columns and `known_words` without dropping anything.
         *
         * Note what this does *not* buy: every surviving content row lands at `parser_version = 0`
         * and is therefore stale, so the first sync re-reads all of them anyway. What it preserves
         * is `schedules` and `sync_state` — a ~1,000-row pull and the events heartbeat — and it
         * keeps the old content answering the notification and the word index while the drain runs.
         *
         * DDL is copied verbatim from `schemas/3.json`. Room validates the migrated database
         * against what it would have built itself and throws on *first access*, not at build time,
         * so pasting Room's own statement is the only way to be sure. `ALTER TABLE ADD COLUMN` is
         * not idempotent, unlike its neighbour — re-registering this migration would throw.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `card_content` " +
                        "ADD COLUMN `parser_version` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `card_content` " +
                        "ADD COLUMN `is_sentence` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `known_words` (`hanzi` TEXT NOT NULL, " +
                        "`pinyin` TEXT, `english` TEXT, `source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`hanzi`))",
                )
            }
        }

        fun get(context: Context): MandopopDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MandopopDatabase::class.java,
                    "mandopop.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    // Scoped to version 1, the only one that really was disposable. A blanket
                    // fallback also covers *downgrades*, so flashing an older build while
                    // debugging would drop the content cache and cost ~940 reads on Traverse's
                    // project to rebuild — and from v3 on, a bump with no migration would do the
                    // same silently. Both now throw at open instead: recoverable by reinstalling,
                    // and impossible to miss.
                    //
                    // Not `(1, 2)`. Listing a version that a registered migration *starts* from
                    // makes Room reject the builder outright — `IllegalArgumentException` at first
                    // database access, which here means the app dies on launch.
                    // dropAllTables = false is the pre-2.7 semantics spelled out: mandopop.db has
                    // only Room tables, so nothing changes beyond dodging the deprecated overload.
                    .fallbackToDestructiveMigrationFrom(false, 1)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
