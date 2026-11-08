package dev.yourstream.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Search index (v7): one denormalized row per library item, rebuilt from the library tables
 * whenever triggers mark the item dirty. Everything a search filters or matches on is here, so a
 * query touches one table plus its full-text index. Rebuildable: no user data.
 *
 * Set-valued columns are ",a,b," strings (`LIKE '%,ta,%'`); text columns are normalized words
 * (see SearchText) for the FTS index.
 */
@Entity(
    tableName = "search_doc",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("kind"), Index("year"), Index("latest_added_at"), Index("sort_title"), Index("rating")],
)
data class SearchDocEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "sort_title") val sortTitle: String,
    val year: Int?,
    val languages: String,
    val genres: String,
    val seasons: String,
    @ColumnInfo(name = "max_resolution") val maxResolution: Int?,
    val qualities: String,
    @ColumnInfo(name = "chat_ids") val chatIds: String,
    val providers: String,
    val rating: Double?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "total_size") val totalSize: Long,
    val hidden: Boolean,
    /** Display + normalized title words. */
    @ColumnInfo(name = "text_title") val textTitle: String,
    /** Official/original titles, library aliases and keys, joined compounds ("spiderman"). */
    @ColumnInfo(name = "text_alt") val textAlt: String,
    /** Distinct words of the original file names (bounded). */
    @ColumnInfo(name = "text_files") val textFiles: String,
    @ColumnInfo(name = "text_chats") val textChats: String,
    /** Genre, language (English and native) and provider names. */
    @ColumnInfo(name = "text_extra") val textExtra: String,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)

@Fts4(contentEntity = SearchDocEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "search_fts")
data class SearchFtsEntity(
    @ColumnInfo(name = "text_title") val textTitle: String,
    @ColumnInfo(name = "text_alt") val textAlt: String,
    @ColumnInfo(name = "text_files") val textFiles: String,
    @ColumnInfo(name = "text_chats") val textChats: String,
    @ColumnInfo(name = "text_extra") val textExtra: String,
)

/** Items whose search row must be rebuilt (filled by triggers, drained by the search indexer). */
@Entity(tableName = "search_dirty")
data class SearchDirtyEntity(@PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long)
