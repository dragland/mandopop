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
                for (variant in variants) {
                    val entries = queryKey(variant, limit.coerceIn(1, 10))
                    if (entries.isNotEmpty()) return@withContext entries
                }
                emptyList()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Dictionary lookup failed", error)
                emptyList()
            }
        }
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
        private const val EXPECTED_USER_VERSION = 1
    }
}
