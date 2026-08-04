package com.mandopop.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

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

    /** Course author, needed to build card-content paths. Uniform across the deck in practice. */
    @Query("SELECT author_user_name FROM schedules WHERE author_user_name != '' LIMIT 1")
    suspend fun anyAuthorUserName(): String?
}

@Dao
interface CardContentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<CardContentEntity>)

    @Query("DELETE FROM card_content")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM card_content WHERE english IS NOT NULL")
    suspend fun resolvedCount(): Int

    /**
     * Due cards we have not looked at yet, so content backfill can run a bounded batch per sync
     * instead of stalling the first one on ~900 network reads.
     */
    @Query(
        """
        SELECT s.card_id FROM schedules s
        LEFT JOIN card_content c ON c.card_id = s.card_id
        WHERE s.suspended = 0 AND s.due_time_ms < :boundaryMs AND c.card_id IS NULL
          AND s.template NOT LIKE '%ACTOR REVIEW' AND s.template NOT LIKE '%SET REVIEW'
        GROUP BY s.card_id
        LIMIT :limit
        """,
    )
    suspend fun dueCardsMissingContent(boundaryMs: Long, limit: Int): List<String>

    /**
     * Drops words scraped from cards that teach a pinyin sound rather than a word.
     *
     * ACTOR and SET cards are keyed by an initial (`b-`) or final (`-ang`) and have no headword at
     * all, so any hanzi on them belongs to the mnemonic story — `-a` resolved to 八 and
     * `*Null* {INITIAL}` to 介. Extracting those produces vocabulary the user never learned.
     * PROP cards are deliberately *not* excluded: radicals like 一 and 十 are also real words.
     */
    @Query(
        """
        DELETE FROM card_content WHERE card_id IN (
            SELECT card_id FROM schedules
            WHERE template LIKE '%ACTOR REVIEW' OR template LIKE '%SET REVIEW'
        )
        """,
    )
    suspend fun deleteSoundOnlyCards(): Int

    /** One resolved word that is due, preferring the most-forgotten card so it is worth showing. */
    @Query(
        """
        SELECT c.* FROM card_content c
        JOIN schedules s ON s.card_id = c.card_id
        WHERE c.english IS NOT NULL AND s.suspended = 0 AND s.due_time_ms < :boundaryMs
        ORDER BY s.lapses DESC, s.due_time_ms ASC
        LIMIT 1
        """,
    )
    suspend fun dueExample(boundaryMs: Long): CardContentEntity?
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)
}

@Database(
    entities = [ScheduleEntity::class, CardContentEntity::class, SyncStateEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MandopopDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun cardContentDao(): CardContentDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile
        private var instance: MandopopDatabase? = null

        fun get(context: Context): MandopopDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MandopopDatabase::class.java,
                    "mandopop.db",
                )
                    // Every row here is a cache of remote state, so throwing it away on a schema
                    // change is safe — the next sync repopulates it.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
