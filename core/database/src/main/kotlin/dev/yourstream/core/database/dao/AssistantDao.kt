package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** One episode with its series and watch progress (assistant episode lists). */
data class AssistantEpisodeRow(
    @ColumnInfo(name = "episode_id") val episodeId: Long,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "series_title") val seriesTitle: String,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    val number: Int,
    val title: String?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val completed: Boolean,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long?,
    @ColumnInfo(name = "play_count") val playCount: Int = 0,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long? = null,
)

/** A library item as the assistant ranks it (from the search index + live watch state). */
data class AssistantItemRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    val languages: String,
    val genres: String,
    val rating: Double?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "episode_count") val episodeCount: Int,
    @ColumnInfo(name = "watched_count") val watchedCount: Int,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long?,
)

/** Languages/genres/kind of something the user watched, weighted by how recently. */
data class WatchProfileRow(
    val kind: String,
    val languages: String,
    val genres: String,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    val completed: Boolean,
)

/**
 * Read-only queries for the local media assistant. Filters vary per request, so the SQL is built
 * by the assistant engine (bound arguments only) and run through [RawQuery].
 */
@Dao
interface AssistantDao {
    @RawQuery
    suspend fun episodes(query: SupportSQLiteQuery): List<AssistantEpisodeRow>

    @RawQuery
    suspend fun items(query: SupportSQLiteQuery): List<AssistantItemRow>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

    /** One row per watched (or started) unit, most recent first. */
    @Query(
        """SELECT d.kind, d.languages, d.genres, ws.last_watched_at, ws.completed
        FROM watch_state ws JOIN search_doc d ON d.item_id = ws.item_id
        WHERE ws.position_ms > 0 OR ws.completed = 1
        ORDER BY ws.last_watched_at DESC LIMIT :limit""",
    )
    suspend fun watchProfile(limit: Int = 200): List<WatchProfileRow>
}
