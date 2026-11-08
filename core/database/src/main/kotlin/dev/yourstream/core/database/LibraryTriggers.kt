package dev.yourstream.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Change tracking for the library sync, done by SQLite itself: any insert/update of an indexed
 * file or of its parsed metadata queues the file in library_pending; links removed by cascades
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

    private val STATEMENTS = listOf(
        "DROP TRIGGER IF EXISTS lib_media_insert",
        "DROP TRIGGER IF EXISTS lib_media_update",
        "DROP TRIGGER IF EXISTS lib_parsed_insert",
        "DROP TRIGGER IF EXISTS lib_parsed_update",
        "DROP TRIGGER IF EXISTS lib_link_delete",
        "CREATE TRIGGER lib_media_insert AFTER INSERT ON media BEGIN ${queue("library_pending", "media_id", "NEW.id")} END",
        "CREATE TRIGGER lib_media_update AFTER UPDATE ON media BEGIN ${queue("library_pending", "media_id", "NEW.id")} END",
        "CREATE TRIGGER lib_parsed_insert AFTER INSERT ON media_parsed BEGIN ${queue("library_pending", "media_id", "NEW.media_id")} END",
        "CREATE TRIGGER lib_parsed_update AFTER UPDATE ON media_parsed BEGIN ${queue("library_pending", "media_id", "NEW.media_id")} END",
        "CREATE TRIGGER lib_link_delete AFTER DELETE ON media_link BEGIN ${queue("library_dirty_item", "item_id", "OLD.item_id")} END",
    )

    /** Idempotent: (re)creates the triggers so definition changes take effect on the next open. */
    fun create(db: SupportSQLiteDatabase) = STATEMENTS.forEach(db::execSQL)
}
