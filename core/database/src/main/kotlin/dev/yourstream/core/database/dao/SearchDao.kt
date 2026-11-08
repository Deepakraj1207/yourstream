package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import dev.yourstream.core.database.entity.SearchDocEntity

/** Inputs for one item's search row. */
data class SearchItemSource(
    val id: Long,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "sort_title") val sortTitle: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    val year: Int?,
    @ColumnInfo(name = "search_text") val searchText: String,
    val hidden: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "max_resolution") val maxResolution: Int?,
    @ColumnInfo(name = "total_size") val totalSize: Long,
    @ColumnInfo(name = "meta_title") val metaTitle: String?,
    @ColumnInfo(name = "original_title") val originalTitle: String?,
    val genres: String?,
    val provider: String?,
    val rating: Double?,
    @ColumnInfo(name = "original_language") val originalLanguage: String?,
)

data class SearchFileSource(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    val resolution: Int?,
    val quality: String?,
)

data class ItemValue(@ColumnInfo(name = "item_id") val itemId: Long, val value: String)
data class ChatTitle(@ColumnInfo(name = "chat_id") val chatId: Long, val title: String)
data class SearchVocabularyRow(
    @ColumnInfo(name = "text_title") val textTitle: String,
    @ColumnInfo(name = "text_alt") val textAlt: String,
    @ColumnInfo(name = "text_chats") val textChats: String,
    @ColumnInfo(name = "text_extra") val textExtra: String,
)

/** A search candidate: the index row plus live watch state. */
data class SearchRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    val languages: String,
    val genres: String,
    val seasons: String,
    @ColumnInfo(name = "max_resolution") val maxResolution: Int?,
    val qualities: String,
    val providers: String,
    val rating: Double?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "total_size") val totalSize: Long,
    @ColumnInfo(name = "text_title") val textTitle: String,
    @ColumnInfo(name = "text_alt") val textAlt: String,
    @ColumnInfo(name = "text_files") val textFiles: String,
    @ColumnInfo(name = "text_chats") val textChats: String,
    @ColumnInfo(name = "text_extra") val textExtra: String,
    @ColumnInfo(name = "watch_state") val watchState: String,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long?,
)

data class IdRow(@ColumnInfo(name = "item_id") val itemId: Long)
data class CountRow(val n: Int)

@Dao
interface SearchDao {
    @Query("SELECT item_id FROM search_dirty LIMIT :limit")
    suspend fun dirty(limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM search_dirty")
    suspend fun dirtyCount(): Int

    @Query("DELETE FROM search_dirty WHERE item_id IN (:ids)")
    suspend fun clearDirty(ids: List<Long>)

    @Query("INSERT OR IGNORE INTO search_dirty (item_id) SELECT id FROM library_item")
    suspend fun queueAll()

    @Upsert
    suspend fun upsertDocs(docs: List<SearchDocEntity>)

    @Query("DELETE FROM search_doc WHERE item_id IN (:ids)")
    suspend fun deleteDocs(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM search_doc")
    suspend fun docCount(): Int

    @Query(
        """SELECT i.id, i.kind, i.title, i.sort_title, i.normalized_title, i.year, i.search_text, i.hidden, i.added_at, i.latest_added_at,
            i.available_count, i.max_resolution, i.total_size, md.title AS meta_title, md.original_title, md.genres, md.provider, md.rating,
            md.original_language
        FROM library_item i LEFT JOIN item_metadata md ON md.item_id = i.id WHERE i.id IN (:ids)""",
    )
    suspend fun itemSources(ids: List<Long>): List<SearchItemSource>

    @Query("SELECT item_id, language AS value FROM library_item_language WHERE item_id IN (:ids)")
    suspend fun languages(ids: List<Long>): List<ItemValue>

    @Query("SELECT series_id AS item_id, CAST(number AS TEXT) AS value FROM season WHERE series_id IN (:ids) AND number >= 0")
    suspend fun seasons(ids: List<Long>): List<ItemValue>

    @Query(
        """SELECT l.item_id, m.file_name, m.chat_id, p.resolution, p.source AS quality
        FROM media_link l JOIN media m ON m.id = l.media_id LEFT JOIN media_parsed p ON p.media_id = m.id
        WHERE l.item_id IN (:ids) AND m.is_deleted = 0""",
    )
    suspend fun files(ids: List<Long>): List<SearchFileSource>

    @Query("SELECT chat_id, title FROM indexed_chat WHERE chat_id IN (:ids)")
    suspend fun chatTitles(ids: List<Long>): List<ChatTitle>

    @Query("SELECT text_title, text_alt, text_chats, text_extra FROM search_doc LIMIT :limit OFFSET :offset")
    suspend fun vocabulary(offset: Int, limit: Int): List<SearchVocabularyRow>

    @Query("SELECT DISTINCT genres AS value, 0 AS item_id FROM search_doc WHERE genres != ',' AND genres != ''")
    suspend fun genreSets(): List<ItemValue>

    @Query("SELECT id FROM episode WHERE series_id = :seriesId AND season_number = :season AND number = :episode LIMIT 1")
    suspend fun episodeId(seriesId: Long, season: Int, episode: Int): Long?

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<SearchRow>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<IdRow>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): CountRow
}
