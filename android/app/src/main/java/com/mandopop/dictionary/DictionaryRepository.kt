package com.mandopop.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DictionaryRepository(private val context: Context) {
    @Volatile
    private var database: SQLiteDatabase? = null
    @Volatile
    private var closed = false

    suspend fun warmUp() {
        withContext(Dispatchers.IO) {
            try {
                getDatabase()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Dictionary warm-up failed", error)
            }
        }
    }

    suspend fun lookup(text: String, limit: Int = 3): List<CedictEntry> {
        val variants = Normalizer.normalizeWord(text) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                // An inflected form can pick up an index key from an incidental mention —
                // CC-CEDICT glosses 哗 as "sound used to call cats" — so taking the first variant
                // with any result at all resolved "cats" to 哗 instead of falling through to
                // cat -> 猫. A variant wins outright only when its top entry means it.
                var fallback: List<CedictEntry>? = null
                for (variant in variants) {
                    val entries = queryKey(variant, limit.coerceIn(1, 10))
                    if (entries.isEmpty()) continue
                    if (GlossMatch.rankOf(entries.first().definitions, variant) != GlossMatch.NO_MATCH) {
                        return@withContext entries
                    }
                    if (fallback == null) fallback = entries
                }
                fallback ?: emptyList()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Dictionary lookup failed", error)
                emptyList()
            }
        }
    }

    /**
     * Reverse lookup: hanzi to dictionary entry.
     *
     * Traverse identifies cards by hanzi, but every feature needs the English side, so this is the
     * bridge between the two. Membership here doubles as the vocabulary filter — Mandarin Blueprint
     * mixes real words in with mnemonic cards (actors, props, pinyin fragments), and only the real
     * words have a CC-CEDICT entry.
     */
    suspend fun lookupBySimplified(hanzi: String, limit: Int = 3): List<CedictEntry> {
        val trimmed = hanzi.trim()
        if (trimmed.isEmpty() || trimmed.length > Normalizer.MAX_SELECTION_LENGTH) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                querySimplified(trimmed, limit.coerceIn(1, 10))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Reverse dictionary lookup failed", error)
                emptyList()
            }
        }
    }

    /**
     * SUBTLEX mass covered by [words] and the whole corpus's mass — the stats line's
     * "% of everyday running Chinese you can read". Per distinct written form (MAX over
     * homograph entries: frequency is form-keyed, so summing per entry would double-count
     * 东西's two readings). The denominator comes from build-time metadata and includes words
     * CC-CEDICT doesn't know, so the percentage stays honest.
     */
    suspend fun frequencyCoverage(words: Collection<String>): Pair<Double, Double> {
        return withContext(Dispatchers.IO) {
            try {
                var mass = 0.0
                for (chunk in words.distinct().chunked(MEMBERSHIP_CHUNK)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    val sql = """
                        SELECT SUM(f) FROM (
                            SELECT MAX(frequency) AS f FROM entries
                            WHERE simplified IN ($placeholders) AND frequency IS NOT NULL
                            GROUP BY simplified
                        )
                    """.trimIndent()
                    getDatabase().rawQuery(sql, chunk.toTypedArray()).use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) mass += cursor.getDouble(0)
                    }
                }
                mass to totalFrequencyMass()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Frequency coverage query failed", error)
                0.0 to 0.0
            }
        }
    }

    private fun totalFrequencyMass(): Double =
        getDatabase().rawQuery(
            "SELECT value FROM metadata WHERE key = 'frequency_total'",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toDoubleOrNull() ?: 0.0 else 0.0
        }

    /**
     * Which of [words] CC-CEDICT actually contains.
     *
     * Segmenting the deck's sentences tests thousands of candidate substrings, and a point query
     * each would mean thousands of round trips for a question that is one indexed scan per chunk.
     * Membership only — callers that need the entry itself still go through [lookupBySimplified].
     */
    suspend fun knownSimplified(words: Collection<String>): Set<String> {
        if (words.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            try {
                val found = mutableSetOf<String>()
                for (chunk in words.distinct().chunked(MEMBERSHIP_CHUNK)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    val sql = "SELECT DISTINCT simplified FROM entries WHERE simplified IN ($placeholders)"
                    getDatabase().rawQuery(sql, chunk.toTypedArray()).use { cursor ->
                        while (cursor.moveToNext()) found += cursor.getString(0)
                    }
                }
                found
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Dictionary membership query failed", error)
                emptySet()
            }
        }
    }

    /**
     * Entries for many hanzi in one pass, keyed by written form.
     *
     * Same reason as [knownSimplified]: rebuilding the known-word index needs an entry for every
     * word it holds, and asking one at a time is hundreds of round trips for what is a handful of
     * indexed scans. Row order within each word is preserved, so callers still see CC-CEDICT's
     * ordering and can apply their own tiebreak.
     */
    suspend fun entriesBySimplified(
        words: Collection<String>,
        limitPerWord: Int = 3,
    ): Map<String, List<CedictEntry>> {
        if (words.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                val found = mutableMapOf<String, MutableList<CedictEntry>>()
                for (chunk in words.distinct().chunked(MEMBERSHIP_CHUNK)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    val sql = """
                        SELECT simplified, pinyin, definitions
                        FROM entries
                        WHERE simplified IN ($placeholders)
                        ORDER BY id
                    """.trimIndent()
                    getDatabase().rawQuery(sql, chunk.toTypedArray()).use { cursor ->
                        val simplifiedIndex = cursor.getColumnIndexOrThrow("simplified")
                        val pinyinIndex = cursor.getColumnIndexOrThrow("pinyin")
                        val definitionsIndex = cursor.getColumnIndexOrThrow("definitions")
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(simplifiedIndex)
                            val entries = found.getOrPut(key) { mutableListOf() }
                            if (entries.size >= limitPerWord) continue
                            entries += CedictEntry(
                                simplified = key,
                                pinyin = cursor.getString(pinyinIndex),
                                definitions = parseDefinitions(cursor.getString(definitionsIndex)),
                            )
                        }
                    }
                }
                found
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Bulk reverse dictionary lookup failed", error)
                emptyMap()
            }
        }
    }

    private fun querySimplified(hanzi: String, limit: Int): List<CedictEntry> {
        val db = getDatabase()
        val entries = mutableListOf<CedictEntry>()
        val sql = """
            SELECT simplified, pinyin, definitions
            FROM entries
            WHERE simplified = ?
            ORDER BY id
            LIMIT $limit
        """.trimIndent()

        db.rawQuery(sql, arrayOf(hanzi)).use { cursor ->
            val simplifiedIndex = cursor.getColumnIndexOrThrow("simplified")
            val pinyinIndex = cursor.getColumnIndexOrThrow("pinyin")
            val definitionsIndex = cursor.getColumnIndexOrThrow("definitions")

            while (cursor.moveToNext()) {
                entries += CedictEntry(
                    simplified = cursor.getString(simplifiedIndex),
                    pinyin = cursor.getString(pinyinIndex),
                    definitions = parseDefinitions(cursor.getString(definitionsIndex)),
                )
            }
        }

        return entries
    }

    private fun queryKey(key: String, limit: Int): List<CedictEntry> {
        val db = getDatabase()
        val entries = mutableListOf<CedictEntry>()
        val sql = """
            SELECT entries.simplified, entries.pinyin, entries.definitions
            FROM lookup_keys
            JOIN entries ON entries.id = lookup_keys.entry_id
            WHERE lookup_keys.key = ?
            ORDER BY lookup_keys.rank, entries.id
            LIMIT $limit
        """.trimIndent()

        db.rawQuery(sql, arrayOf(key)).use { cursor ->
            val simplifiedIndex = cursor.getColumnIndexOrThrow("simplified")
            val pinyinIndex = cursor.getColumnIndexOrThrow("pinyin")
            val definitionsIndex = cursor.getColumnIndexOrThrow("definitions")

            while (cursor.moveToNext()) {
                entries += CedictEntry(
                    simplified = cursor.getString(simplifiedIndex),
                    pinyin = cursor.getString(pinyinIndex),
                    definitions = parseDefinitions(cursor.getString(definitionsIndex)),
                )
            }
        }

        return entries
    }

    @Synchronized
    private fun getDatabase(): SQLiteDatabase {
        check(!closed) { "Dictionary repository is closed" }
        database?.takeIf { it.isOpen }?.let { return it }
        copyDatabaseIfNeeded()
        check(!closed) { "Dictionary repository is closed" }
        val file = context.getDatabasePath(DB_NAME)
        val opened = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        database = opened
        if (closed) {
            database = null
            opened.close()
            error("Dictionary repository is closed")
        }
        return opened
    }

    @Synchronized
    private fun copyDatabaseIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val file = context.getDatabasePath(DB_NAME)
        val assetHash = context.assets.open(HASH_NAME).bufferedReader().use { it.readText().trim() }
        val copiedHash = prefs.getString(KEY_COPIED_HASH, null)
        if (file.exists() && copiedHash == assetHash) return

        file.parentFile?.mkdirs()
        val tempFile = context.getDatabasePath("$DB_NAME.tmp")
        context.assets.open(DB_NAME).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        validateDatabase(tempFile.absolutePath)
        Files.move(
            tempFile.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        if (!prefs.edit().putString(KEY_COPIED_HASH, assetHash).commit()) {
            Log.w(TAG, "Failed to persist copied dictionary hash")
        }
    }

    fun close() {
        closed = true
        database?.close()
        database = null
    }

    private fun validateDatabase(path: String) {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val integrity = db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            check(integrity == "ok") { "Dictionary quick_check failed: $integrity" }

            val userVersion = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            check(userVersion == EXPECTED_USER_VERSION) {
                "Unexpected dictionary user_version: $userVersion"
            }
        }
    }

    private fun parseDefinitions(raw: String): List<String> {
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getString(index) }
        }.getOrElse {
            listOf(raw)
        }
    }

    companion object {
        private const val TAG = "DictionaryRepository"
        private const val DB_NAME = "cedict.db"
        private const val HASH_NAME = "cedict.sha256"
        private const val PREFS_NAME = "mandopop_dictionary"
        private const val KEY_COPIED_HASH = "copied_hash"
        // Must match SCHEMA_VERSION in android/scripts/build_dictionary.py.
        private const val EXPECTED_USER_VERSION = 3
        /** SQLite's default parameter ceiling is 999; this keeps well clear of it. */
        private const val MEMBERSHIP_CHUNK = 400

        @Volatile
        private var sharedInstance: DictionaryRepository? = null

        /**
         * The process-lifetime handle. Per-caller instances were an audited leak: every
         * `TraverseSync` construction (each worker run, each notification broadcast, each
         * shade-pull) opened its own SQLite connection and nothing ever closed it — hundreds of
         * live handles a day. One connection per process, never closed; the constructor stays
         * public only for JVM tests.
         */
        fun shared(context: Context): DictionaryRepository =
            sharedInstance ?: synchronized(this) {
                sharedInstance
                    ?: DictionaryRepository(context.applicationContext).also { sharedInstance = it }
            }
    }
}
