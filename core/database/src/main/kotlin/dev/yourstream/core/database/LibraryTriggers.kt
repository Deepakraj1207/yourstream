package dev.yourstream.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Change tracking for the library sync, done by SQLite itself: an insert, or an update that
 * changes a column the library uses, of an indexed file or its parsed metadata queues the file
 * in library_pending (rewriting identical rows queues nothing); links removed by cascades
 * (chat purged, file row deleted) queue their item in library_dirty_item. The sync then touches
 * only what changed instead of scanning the whole index.
 *
 * The inserts are guarded with NOT EXISTS instead of INSERT OR IGNORE: SQLite lets the firing
 * statement's conflict clause (Room uses UPDATE OR ABORT) override the trigger's own, which
 * would turn an already-queued row into a failed update.
 */
object LibraryTriggers {
    private fun queue(table: String, column: String, value: String) =
        "INSERT INTO $table ($column) SELECT $value WHERE NOT EXISTS (SELECT 1 FROM $table WHERE $column = $value);"

    /**
     * Columns that change without changing what the library shows: bookkeeping timestamps and the
     * download state (read live). Before, every rescan "touch" and every download stop re-queued
     * the file, so a full rescan re-synced the whole library.
     */
    private val VOLATILE_MEDIA = setOf("indexed_at", "updated_at", "last_seen_at", "download_state", "downloaded_bytes", "local_path")

    /** `WHEN OLD.a IS NOT NEW.a OR …` over the table's current columns (minus [exclude]). */
    internal fun changed(db: SupportSQLiteDatabase, table: String, exclude: Set<String> = emptySet()): String {
        val cols = db.query("PRAGMA table_info(`$table`)").use { c ->
            val i = c.getColumnIndex("name"); buildList { while (c.moveToNext()) add(c.getString(i)) }
        }.filter { it !in exclude }
        return if (cols.isEmpty()) "" else "WHEN " + cols.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it`" }
    }

    private fun statements(db: SupportSQLiteDatabase) = listOf(
        "DROP TRIGGER IF EXISTS lib_media_insert",
        "DROP TRIGGER IF EXISTS lib_media_update",
        "DROP TRIGGER IF EXISTS lib_parsed_insert",
        "DROP TRIGGER IF EXISTS lib_parsed_update",
        "DROP TRIGGER IF EXISTS lib_link_delete",
        "CREATE TRIGGER lib_media_insert AFTER INSERT ON media BEGIN ${queue("library_pending", "media_id", "NEW.id")} END",
        "CREATE TRIGGER lib_media_update AFTER UPDATE ON media ${changed(db, "media", VOLATILE_MEDIA)} BEGIN ${queue("library_pending", "media_id", "NEW.id")} END",
        "CREATE TRIGGER lib_parsed_insert AFTER INSERT ON media_parsed BEGIN ${queue("library_pending", "media_id", "NEW.media_id")} END",
        "CREATE TRIGGER lib_parsed_update AFTER UPDATE ON media_parsed ${changed(db, "media_parsed")} BEGIN ${queue("library_pending", "media_id", "NEW.media_id")} END",
        "CREATE TRIGGER lib_link_delete AFTER DELETE ON media_link BEGIN ${queue("library_dirty_item", "item_id", "OLD.item_id")} END",
    )

    /** Idempotent: (re)creates the triggers so definition changes take effect on the next open. */
    fun create(db: SupportSQLiteDatabase) = statements(db).forEach(db::execSQL)
}
