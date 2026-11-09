package dev.yourstream.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase.JournalMode
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/** How the database came up at this start. */
data class DatabaseOpenReport(
    val outcome: Outcome,
    /** Version on disk before opening (null: no database yet / unreadable). */
    val previousVersion: Int?,
    val currentVersion: Int,
    /** Copy taken before a schema upgrade. */
    val backup: File? = null,
    /** Where a database that could not be opened was moved (never deleted). */
    val quarantined: File? = null,
    /** Rows recovered per table after a failed open. */
    val salvaged: Map<String, Int> = emptyMap(),
    val error: String? = null,
    val tookMs: Long = 0,
) {
    enum class Outcome {
        CREATED,
        OPENED,
        /** Schema upgraded by migrations (a backup was taken first). */
        MIGRATED,
        /** Opening failed (migration error, downgrade, corruption): a new database was created and user data copied over. */
        SALVAGED,
        /** Opening failed and nothing could be recovered: a new, empty database (the old file is kept). */
        RECREATED,
    }
}

/**
 * Opens the database without ever losing it:
 *  1. before a schema upgrade, the files are copied to `databases/backups/` (two kept);
 *  2. the database is opened *now* (not lazily on first query), so migration problems surface here;
 *  3. if that fails — a broken migration, a downgrade after installing an older build, a
 *     corrupted file — the old files are moved to `databases/quarantine/`, a fresh database is
 *     created and everything that still fits the current schema is copied back
 *     ([DatabaseSalvage]): user data (progress, favorites, preferences) and the library.
 *     Rebuildable indexes (FTS, search) are rebuilt.
 *
 * Destructive migration is never used. The app always gets a working database.
 */
object DatabaseOpener {
    const val CURRENT_VERSION = 10

    fun open(context: Context, name: String = YourStreamDatabase.NAME, log: (String, Throwable?) -> Unit = { _, _ -> }): Pair<YourStreamDatabase, DatabaseOpenReport> {
        val start = System.nanoTime()
        val file = context.getDatabasePath(name)
        val existed = file.exists() && file.length() > 0
        val onDisk = if (existed) readVersion(file) else null
        var backup: File? = null
        // A file SQLite cannot read: move it aside *before* Android's default corruption handler
        // would delete it.
        var quarantinedEarly: File? = null
        if (existed && onDisk == null) {
            quarantinedEarly = quarantine(file)
            log("Database file is unreadable; moved to ${quarantinedEarly?.name}", null)
        }
        if (onDisk != null && onDisk in 1 until CURRENT_VERSION) {
            backup = backup(file, onDisk)
            log("Upgrading database v$onDisk → v$CURRENT_VERSION (backup ${backup?.name ?: "failed"})", null)
        }
        val first = runCatching { build(context, name).also { it.openHelper.writableDatabase } }
        first.getOrNull()?.let { db ->
            if (quarantinedEarly != null) {
                return db to DatabaseOpenReport(DatabaseOpenReport.Outcome.RECREATED, null, CURRENT_VERSION, quarantined = quarantinedEarly,
                    error = "unreadable database file", tookMs = ms(start))
            }
            val outcome = when {
                !existed -> DatabaseOpenReport.Outcome.CREATED
                onDisk != null && onDisk < CURRENT_VERSION -> DatabaseOpenReport.Outcome.MIGRATED
                else -> DatabaseOpenReport.Outcome.OPENED
            }
            return db to DatabaseOpenReport(outcome, onDisk, CURRENT_VERSION, backup, tookMs = ms(start))
        }

        val error = first.exceptionOrNull()!!
        log("Database could not be opened (v${onDisk ?: "?"}): ${error::class.java.simpleName}: ${error.message}", error)
        val quarantined = quarantine(file)
        val db = build(context, name).also { it.openHelper.writableDatabase }
        val source = quarantined?.takeIf { readVersion(it) != null } ?: backup?.takeIf { readVersion(it) != null }
        val salvaged = source?.let { runCatching { DatabaseSalvage.copyInto(db.openHelper.writableDatabase, it) }.onFailure { e -> log("Salvage failed", e) }.getOrNull() }
        val outcome = if (!salvaged.isNullOrEmpty() && salvaged.values.sum() > 0) DatabaseOpenReport.Outcome.SALVAGED else DatabaseOpenReport.Outcome.RECREATED
        log("Database ${outcome.name.lowercase()}: ${salvaged?.values?.sum() ?: 0} rows recovered", null)
        return db to DatabaseOpenReport(outcome, onDisk, CURRENT_VERSION, backup, quarantined, salvaged.orEmpty(), "${error::class.java.simpleName}: ${error.message}", ms(start))
    }

    private fun build(context: Context, name: String) = Room.databaseBuilder(context.applicationContext, YourStreamDatabase::class.java, name)
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(YourStreamDatabase.CALLBACK)
        .openHelperFactory(QuarantiningOpenHelperFactory { f -> quarantineCopy(f) })
        .build()

    /** Corruption found while running: keep a copy before the framework deletes the file. */
    private fun quarantineCopy(path: String) {
        runCatching {
            val file = File(path)
            val dir = File(file.parentFile, "quarantine").apply { mkdirs() }
            val target = File(dir, "${file.name}.${System.currentTimeMillis()}")
            companions(file).filter { it.exists() }.forEach { it.copyTo(File(target.path + it.name.removePrefix(file.name)), overwrite = true) }
        }
    }

    /** user_version of a database file, or null when it is not a readable SQLite database. */
    fun readVersion(file: File): Int? = runCatching {
        // Header first: never hand a non-database to SQLite. And a no-op error handler: the
        // default one *deletes* a file it considers corrupt, even when only reading its version.
        val header = ByteArray(16).also { b -> file.inputStream().use { it.read(b) } }
        if (String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") return null
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, android.database.DatabaseErrorHandler { }).use { it.version }
    }.getOrNull()

    private fun companions(file: File) = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))

    private fun backup(file: File, version: Int): File? = runCatching {
        val dir = File(file.parentFile, "backups").apply { mkdirs() }
        val target = File(dir, "${file.name}.v$version.${System.currentTimeMillis()}")
        companions(file).filter { it.exists() }.forEach { it.copyTo(File(target.path + it.name.removePrefix(file.name)), overwrite = true) }
        // Keep the two newest backups.
        dir.listFiles { f -> f.name.startsWith("${file.name}.v") && !f.name.endsWith("-wal") && !f.name.endsWith("-shm") && !f.name.endsWith("-journal") }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { old -> companions(old).forEach { it.delete() } }
        target
    }.getOrNull()

    private fun quarantine(file: File): File? = runCatching {
        if (!file.exists()) return null
        val dir = File(file.parentFile, "quarantine").apply { mkdirs() }
        val target = File(dir, "${file.name}.${System.currentTimeMillis()}")
        companions(file).filter { it.exists() }.forEach { f -> check(f.renameTo(File(target.path + f.name.removePrefix(file.name)))) { "could not move ${f.name}" } }
        target
    }.getOrNull()

    fun backups(context: Context, name: String = YourStreamDatabase.NAME): List<File> =
        File(context.getDatabasePath(name).parentFile, "backups").listFiles()?.filter { !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun quarantined(context: Context, name: String = YourStreamDatabase.NAME): List<File> =
        File(context.getDatabasePath(name).parentFile, "quarantine").listFiles()?.filter { !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
}

/**
 * Copies rows from another database file (any version) into the current schema: for every
 * table both have, the columns both have. Rows that no longer fit (missing NOT NULL values,
 * broken references) are dropped individually. Rebuildable data (FTS, the search index) is
 * rebuilt from the copied tables instead of copied.
 */
object DatabaseSalvage {
    private val REBUILT = setOf("search_doc", "search_fts", "search_dirty", "media_fts", "library_item_fts", "room_master_table", "android_metadata")
    private val FTS = listOf("media_fts", "library_item_fts", "search_fts")

    fun copyInto(db: SupportSQLiteDatabase, source: File): Map<String, Int> {
        val copied = LinkedHashMap<String, Int>()
        db.execSQL("PRAGMA foreign_keys = OFF")
        db.execSQL("ATTACH DATABASE ? AS old", arrayOf(source.path))
        try {
            val tables = names(db, "SELECT name FROM old.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
                .filter { it !in REBUILT && FTS.none { f -> it.startsWith(f + "_") } }
            val current = names(db, "SELECT name FROM main.sqlite_master WHERE type = 'table'").toSet()
            for (t in tables.filter { it in current }) {
                val cols = columns(db, "main", t).intersect(columns(db, "old", t).toSet()).joinToString { "`$it`" }
                if (cols.isEmpty()) continue
                val before = count(db, "main", t)
                runCatching { db.execSQL("INSERT OR IGNORE INTO main.`$t` ($cols) SELECT $cols FROM old.`$t`") }
                    .onFailure { rowByRow(db, t, cols) }
                copied[t] = count(db, "main", t) - before
            }
        } finally {
            db.execSQL("DETACH DATABASE old")
            db.execSQL("PRAGMA foreign_keys = ON")
        }
        // Drop rows whose parent did not survive.
        DatabaseIntegrity.deleteForeignKeyViolations(db)
        DatabaseIntegrity.rebuildIndexes(db)
        return copied
    }

    /** One bad row must not lose the whole table. */
    private fun rowByRow(db: SupportSQLiteDatabase, t: String, cols: String) {
        val rowids = db.query("SELECT rowid FROM old.`$t`").use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } }
        rowids.forEach { id -> runCatching { db.execSQL("INSERT OR IGNORE INTO main.`$t` ($cols) SELECT $cols FROM old.`$t` WHERE rowid = $id") } }
    }

    private fun names(db: SupportSQLiteDatabase, sql: String) = db.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    private fun columns(db: SupportSQLiteDatabase, schema: String, t: String) = db.query("PRAGMA $schema.table_info(`$t`)").use { c ->
        val i = c.getColumnIndex("name"); buildList { while (c.moveToNext()) add(c.getString(i)) }
    }
    private fun count(db: SupportSQLiteDatabase, schema: String, t: String) = db.query("SELECT COUNT(*) FROM $schema.`$t`").use { it.moveToFirst(); it.getInt(0) }
}

data class IntegrityReport(
    val ok: Boolean,
    val quickCheck: String,
    val foreignKeyViolations: Int,
    val brokenFts: List<String>,
    val invalidCacheRows: Int,
    val invalidMetadataRows: Int,
    val counts: Map<String, Int>,
    val sizeBytes: Long,
    val version: Int,
)

data class RepairReport(val actions: List<String>, val after: IntegrityReport)

/** Health check and repair of the live database (diagnostics screen, weekly maintenance). */
object DatabaseIntegrity {
    private val COUNTED = listOf("media", "library_item", "episode", "watch_state", "watch_history", "favorite", "item_metadata", "metadata_cache", "catalog_cache", "search_doc", "search_dirty")
    private val FTS = listOf("media_fts", "library_item_fts", "search_fts")

    fun check(db: SupportSQLiteDatabase, file: File? = null): IntegrityReport {
        val quick = runCatching { db.query("PRAGMA quick_check").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.joinToString("; ") }
            .getOrElse { "failed: ${it.message}" }
        val fk = runCatching { db.query("PRAGMA foreign_key_check").use { it.count } }.getOrDefault(-1)
        val brokenFts = FTS.filter { t -> runCatching { db.execSQL("INSERT INTO $t($t) VALUES('integrity-check')") }.isFailure }
        val invalidCache = invalidJson(db, "metadata_cache", "payload") + invalidJson(db, "catalog_cache", "payload")
        val invalidMeta = invalidJson(db, "item_metadata", "`cast`")
        val counts = COUNTED.associateWith { t -> runCatching { db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) } }.getOrDefault(-1) }
        return IntegrityReport(
            ok = quick == "ok" && fk == 0 && brokenFts.isEmpty() && invalidCache == 0 && invalidMeta == 0,
            quickCheck = quick, foreignKeyViolations = fk, brokenFts = brokenFts, invalidCacheRows = invalidCache, invalidMetadataRows = invalidMeta,
            counts = counts, sizeBytes = file?.let { f -> listOf(f, File(f.path + "-wal")).sumOf { it.length() } } ?: 0, version = db.version,
        )
    }

    /**
     * Fixes what can be fixed without losing user data: drops rows with broken references,
     * deletes unreadable cache entries (refetched), resets corrupted cast lists and schedules
     * that metadata for refresh, rebuilds full-text indexes, re-queues the search index, and
     * checkpoints the WAL.
     */
    fun repair(db: SupportSQLiteDatabase, file: File? = null): RepairReport {
        val actions = mutableListOf<String>()
        deleteForeignKeyViolations(db).takeIf { it > 0 }?.let { actions += "removed $it rows with broken references" }
        if (jsonSupported(db)) {
            for (t in listOf("metadata_cache", "catalog_cache")) {
                runCatching { db.delete(t, "payload IS NOT NULL AND json_valid(payload) = 0", null) }.getOrDefault(0).takeIf { it > 0 }?.let { actions += "deleted $it unreadable $t entries (will be fetched again)" }
            }
            runCatching {
                db.query("SELECT item_id FROM item_metadata WHERE json_valid(`cast`) = 0").use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { ids ->
                ids.forEach { db.execSQL("UPDATE item_metadata SET `cast` = '[]', fetched_at = 0 WHERE item_id = ?", arrayOf(it)) }
                actions += "reset corrupted metadata of ${ids.size} items (refreshed on the next metadata sync)"
            }
        }
        rebuildIndexes(db)
        actions += "rebuilt full-text indexes and re-queued the search index"
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        return RepairReport(actions, check(db, file))
    }

    fun deleteForeignKeyViolations(db: SupportSQLiteDatabase): Int {
        val rows = runCatching {
            db.query("PRAGMA foreign_key_check").use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        }.getOrDefault(emptyList())
        rows.forEach { (table, rowid) -> runCatching { db.execSQL("DELETE FROM `$table` WHERE rowid = ?", arrayOf(rowid)) } }
        return rows.size
    }

    fun rebuildIndexes(db: SupportSQLiteDatabase) {
        FTS.forEach { t -> runCatching { db.execSQL("INSERT INTO $t($t) VALUES('rebuild')") } }
        runCatching { db.execSQL("INSERT OR IGNORE INTO search_dirty (item_id) SELECT id FROM library_item") }
    }

    private fun jsonSupported(db: SupportSQLiteDatabase) = runCatching { db.query("SELECT json_valid('[]')").use { it.moveToFirst() } }.isSuccess

    private fun invalidJson(db: SupportSQLiteDatabase, table: String, column: String): Int =
        if (!jsonSupported(db)) 0
        else runCatching { db.query("SELECT COUNT(*) FROM $table WHERE $column IS NOT NULL AND json_valid($column) = 0").use { it.moveToFirst(); it.getInt(0) } }.getOrDefault(0)
}

/** The database plus how it was opened (for diagnostics). */
class OpenedDatabase(val db: YourStreamDatabase, val report: DatabaseOpenReport, val file: File)

/** Wraps the framework helper so `onCorruption` first copies the file to quarantine. */
class QuarantiningOpenHelperFactory(private val onCorrupt: (String) -> Unit) : androidx.sqlite.db.SupportSQLiteOpenHelper.Factory {
    private val framework = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration): androidx.sqlite.db.SupportSQLiteOpenHelper {
        val inner = configuration.callback
        val wrapped = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(inner.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                db.path?.let(onCorrupt)
                inner.onCorruption(db)
            }
        }
        return framework.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(wrapped).noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery).build(),
        )
    }
}
