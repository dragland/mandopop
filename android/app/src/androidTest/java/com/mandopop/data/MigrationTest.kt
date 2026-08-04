package com.mandopop.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one test standing between an upgrade and a bricked database.
 *
 * Room validates a migrated schema against what it would have built and throws on *first access*,
 * not at build time — and `fallbackToDestructiveMigrationFrom(1)` does not catch that, because the
 * fallback only covers versions with no migration path at all. So a slip in `MIGRATION_2_3`
 * would surface as an exception every time the app touched the database on the user's phone.
 *
 * Also asserts the thing the migration exists for: that existing card content survives, rather than
 * costing ~940 document reads on Traverse's project to refill.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MandopopDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate2To3KeepsCardContentAndSatisfiesRoom() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO card_content (card_id, hanzi, pinyin, english, fetched_at_ms)
                VALUES ('c1', '水', 'shuǐ', 'water', 1000)
                """.trimIndent(),
            )
        }

        // runMigrationsAndValidate is the assertion: it applies the migration and then compares the
        // result column-by-column against the exported v3 schema.
        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true)

        db.query("SELECT hanzi, parser_version, is_sentence FROM card_content").use { cursor ->
            assertTrue("card content should survive the upgrade", cursor.moveToFirst())
            assertEquals("水", cursor.getString(0))
            // Version 0 means "read by a parser older than the current one", so the backfill picks
            // these up as stale and re-reads them once — lazily, rather than all at once.
            assertEquals(0, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM known_words").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
