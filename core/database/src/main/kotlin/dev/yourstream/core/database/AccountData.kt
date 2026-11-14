package dev.yourstream.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The rows that belong to the signed-in Telegram account: indexed chats and files, the library
 * built from them, metadata matches, watch history and progress, track choices, the search index.
 * Signing out removes them (`docs/TELEGRAM_SETUP.md`, "Signing out").
 *
 * Every table is account data unless listed in [KEPT_TABLES], so a table added later is wiped by
 * default rather than leaked to the next account.
 */
object AccountData {
    /** Device preferences, not account data: preferred audio/subtitle languages, public add-on catalog pages. */
    val KEPT_TABLES: Set<String> = setOf("language_settings", "catalog_cache")

    private val INTERNAL = setOf("android_metadata", "room_master_table", "sqlite_sequence")

    /** Account tables in [db] (FTS indexes and their shadow tables excluded: they follow their content tables). */
    fun accountTables(db: SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<Pair<String, String>>()
        db.query("SELECT name, sql FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) tables += c.getString(0) to (c.getString(1) ?: "")
        }
        val virtual = tables.filter { (_, sql) -> sql.startsWith("CREATE VIRTUAL TABLE", ignoreCase = true) }.map { it.first }
        return tables.map { it.first }.filter { name ->
            name !in INTERNAL && name !in KEPT_TABLES && !name.startsWith("sqlite_") && !name.startsWith("room_") &&
                name !in virtual && virtual.none { v -> name.startsWith(v + "_") }
        }
    }

    private fun virtualTables(db: SupportSQLiteDatabase): List<String> {
        val out = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND sql LIKE 'CREATE VIRTUAL TABLE%'").use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    private fun isEmpty(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT EXISTS(SELECT 1 FROM `$table`)").use { c -> !c.moveToFirst() || c.getInt(0) == 0 }

    /** True while any account table has a row. */
    suspend fun hasAccountData(database: YourStreamDatabase): Boolean = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        accountTables(db).any { !isEmpty(db, it) }
    }

    /**
     * Deletes every account row in one transaction. Triggers that mark rows "dirty" while others
     * are deleted are handled by repeating until all account tables are empty; foreign keys are
     * checked at commit. Full-text indexes are rebuilt (empty) at the end. Room observers are
     * notified, so open screens empty at once.
     */
    suspend fun wipe(database: YourStreamDatabase) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            val tables = accountTables(db)
            repeat(MAX_PASSES) {
                if (tables.all { isEmpty(db, it) }) return@repeat
                tables.forEach { db.execSQL("DELETE FROM `$it`") }
            }
            check(tables.all { isEmpty(db, it) }) { "account tables not empty after $MAX_PASSES passes" }
            virtualTables(db).forEach { fts -> db.execSQL("INSERT INTO `$fts`(`$fts`) VALUES('rebuild')") }
        }
    }

    private const val MAX_PASSES = 4
}
