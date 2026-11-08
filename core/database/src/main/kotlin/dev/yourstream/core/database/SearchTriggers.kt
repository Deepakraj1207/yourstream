package dev.yourstream.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Keeps the search index honest without the app having to remember: every change to what search
 * shows (item, applied metadata, linked files, languages, seasons, parsed release info, chat
 * names) queues the affected item in search_dirty. The indexer drains the queue before searching.
 * Watch progress is not indexed (search reads it live), so playback writes trigger nothing.
 */
object SearchTriggers {
    private fun one(value: String) =
        "INSERT INTO search_dirty (item_id) SELECT $value WHERE NOT EXISTS (SELECT 1 FROM search_dirty WHERE item_id = $value);"

    private const val ITEM_COLUMNS = "kind, title, year, search_text, hidden, available_count, max_resolution, season_count, episode_count, total_size, latest_added_at"

    private val NAMES = listOf(
        "search_item_insert", "search_item_update", "search_meta_insert", "search_meta_update", "search_meta_delete",
        "search_link_insert", "search_link_update", "search_link_delete", "search_lang_insert", "search_lang_delete",
        "search_season_insert", "search_season_delete", "search_parsed_update", "search_chat_rename",
    )

    private val CREATE = listOf(
        "CREATE TRIGGER search_item_insert AFTER INSERT ON library_item BEGIN ${one("NEW.id")} END",
        "CREATE TRIGGER search_item_update AFTER UPDATE OF $ITEM_COLUMNS ON library_item BEGIN ${one("NEW.id")} END",
        "CREATE TRIGGER search_meta_insert AFTER INSERT ON item_metadata BEGIN ${one("NEW.item_id")} END",
        "CREATE TRIGGER search_meta_update AFTER UPDATE ON item_metadata BEGIN ${one("NEW.item_id")} END",
        "CREATE TRIGGER search_meta_delete AFTER DELETE ON item_metadata BEGIN ${one("OLD.item_id")} END",
        "CREATE TRIGGER search_link_insert AFTER INSERT ON media_link BEGIN ${one("NEW.item_id")} END",
        "CREATE TRIGGER search_link_update AFTER UPDATE ON media_link BEGIN ${one("NEW.item_id")} ${one("OLD.item_id")} END",
        "CREATE TRIGGER search_link_delete AFTER DELETE ON media_link BEGIN ${one("OLD.item_id")} END",
        "CREATE TRIGGER search_lang_insert AFTER INSERT ON library_item_language BEGIN ${one("NEW.item_id")} END",
        "CREATE TRIGGER search_lang_delete AFTER DELETE ON library_item_language BEGIN ${one("OLD.item_id")} END",
        "CREATE TRIGGER search_season_insert AFTER INSERT ON season BEGIN ${one("NEW.series_id")} END",
        "CREATE TRIGGER search_season_delete AFTER DELETE ON season BEGIN ${one("OLD.series_id")} END",
        """CREATE TRIGGER search_parsed_update AFTER UPDATE ON media_parsed BEGIN
            INSERT INTO search_dirty (item_id) SELECT DISTINCT item_id FROM media_link WHERE media_id = NEW.media_id
              AND item_id NOT IN (SELECT item_id FROM search_dirty); END""",
        """CREATE TRIGGER search_chat_rename AFTER UPDATE OF title ON indexed_chat BEGIN
            INSERT INTO search_dirty (item_id) SELECT DISTINCT l.item_id FROM media_link l JOIN media m ON m.id = l.media_id
              WHERE m.chat_id = NEW.chat_id AND l.item_id NOT IN (SELECT item_id FROM search_dirty); END""",
    )

    /** Idempotent: recreated on every open so definition changes apply. */
    fun create(db: SupportSQLiteDatabase) {
        NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
        CREATE.forEach(db::execSQL)
    }
}
