package dev.yourstream.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signing out removes every account row from the real schema and keeps device preferences. */
@RunWith(RobolectricTestRunner::class)
class AccountDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "account-data-test.db"
    private val db: YourStreamDatabase = DatabaseOpener.open(context, name).first

    @After fun cleanup() {
        db.close()
        context.getDatabasePath(name).parentFile?.deleteRecursively()
    }

    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase
    private fun count(t: String) = sql.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }

    /** One row in [table] (unless a trigger already added it), every column a plain value of its type. */
    private fun seed(table: String, overrides: Map<String, Any> = emptyMap()) {
        val cols = mutableListOf<Pair<String, String>>()
        sql.query("PRAGMA table_info(`$table`)").use { c -> while (c.moveToNext()) cols += c.getString(1) to c.getString(2).uppercase() }
        val values = cols.map { (name, type) ->
            overrides[name] ?: when {
                "INT" in type -> 1
                "REAL" in type -> 1.0
                else -> "x"
            }
        }
        sql.execSQL("INSERT OR IGNORE INTO `$table` (${cols.joinToString { "`${it.first}`" }}) VALUES (${values.joinToString { "?" }})", values.toTypedArray())
    }

    @Test
    fun wipeEmptiesEveryAccountTableAndKeepsDevicePreferences() = runTest {
        val tables = AccountData.accountTables(sql)
        // The tables that matter most are recognised as account data.
        for (t in listOf("indexed_chat", "media", "library_item", "watch_history", "watch_state", "metadata_match", "search_doc", "favorite", "track_preference", "stream_progress")) {
            assertTrue(t in tables, "$t is account data")
        }
        assertFalse("language_settings" in tables)
        assertFalse("media_fts" in tables, "FTS indexes follow their content tables")

        // One row in every account table (foreign keys off while seeding: order does not matter).
        sql.execSQL("PRAGMA foreign_keys = OFF")
        tables.forEach { seed(it, if (it == "media") mapOf("file_name" to "Vikram.2022.mkv", "caption" to "Vikram") else emptyMap()) }
        seed("language_settings")
        seed("catalog_cache")
        sql.execSQL("PRAGMA foreign_keys = ON")
        assertTrue(AccountData.hasAccountData(db))
        assertEquals(1, sql.query("SELECT COUNT(*) FROM media_fts WHERE media_fts MATCH 'vikram'").use { it.moveToFirst(); it.getInt(0) })

        AccountData.wipe(db)

        tables.forEach { assertEquals(0, count(it), "$it emptied") }
        assertFalse(AccountData.hasAccountData(db))
        assertEquals(0, sql.query("SELECT COUNT(*) FROM media_fts WHERE media_fts MATCH 'vikram'").use { it.moveToFirst(); it.getInt(0) }, "search finds nothing")
        assertEquals(1, count("language_settings"), "language preferences stay")
        assertEquals(1, count("catalog_cache"), "public catalog pages stay")
    }

    @Test
    fun wipingAnEmptyDatabaseIsHarmless() = runTest {
        assertFalse(AccountData.hasAccountData(db))
        AccountData.wipe(db)
        assertFalse(AccountData.hasAccountData(db))
    }
}
