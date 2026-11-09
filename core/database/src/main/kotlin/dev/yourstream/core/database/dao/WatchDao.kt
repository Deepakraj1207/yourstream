package dev.yourstream.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.yourstream.core.database.entity.WatchHistoryEntity
import dev.yourstream.core.database.entity.WatchStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {

    @Query("SELECT * FROM watch_state WHERE item_id = :itemId AND episode_key = :episodeKey")
    suspend fun state(itemId: Long, episodeKey: Long): WatchStateEntity?

    @Query("SELECT * FROM watch_state WHERE item_id = :itemId AND episode_key = :episodeKey")
    fun observeState(itemId: Long, episodeKey: Long): Flow<WatchStateEntity?>

    @Upsert
    suspend fun upsertState(state: WatchStateEntity)

    @Upsert
    suspend fun upsertStates(states: List<WatchStateEntity>)

    @Query("SELECT * FROM watch_state WHERE item_id = :itemId")
    suspend fun statesOf(itemId: Long): List<WatchStateEntity>

    @Query("DELETE FROM watch_state WHERE item_id = :itemId AND episode_key = :episodeKey")
    suspend fun deleteState(itemId: Long, episodeKey: Long)

    @Query("SELECT COUNT(*) FROM watch_state w JOIN episode e ON e.id = w.episode_key WHERE w.item_id = :seriesId AND w.completed = 1 AND e.series_id = :seriesId")
    suspend fun completedEpisodeCount(seriesId: Long): Int

    @Query("SELECT MAX(last_watched_at) FROM watch_state WHERE item_id = :itemId")
    suspend fun lastWatched(itemId: Long): Long?

    @Query("UPDATE library_item SET watched_count = :watched, last_watched_at = :lastWatchedAt WHERE id = :itemId")
    suspend fun setItemWatchAggregates(itemId: Long, watched: Int, lastWatchedAt: Long?)

    /** In-progress units, newest first, one per item (the most recent episode of a series). */
    @Query(
        """SELECT ws.item_id, ws.episode_key, i.kind, i.title, i.year, e.season_number, e.number AS episode_number,
               e.title AS episode_title, ws.position_ms, ws.duration_ms, ws.completed, ws.last_watched_at, ws.last_media_id
        FROM watch_state ws JOIN library_item i ON i.id = ws.item_id LEFT JOIN episode e ON e.id = ws.episode_key
        WHERE ws.completed = 0 AND ws.position_ms > 0 AND i.hidden = 0
          AND ws.last_watched_at = (SELECT MAX(w2.last_watched_at) FROM watch_state w2
                                    WHERE w2.item_id = ws.item_id AND w2.completed = 0 AND w2.position_ms > 0)
        ORDER BY ws.last_watched_at DESC LIMIT :limit""",
    )
    fun continueWatching(limit: Int): Flow<List<WatchEntryRow>>

    /** Every watched unit, newest first (paged: can be long). */
    @Query(
        """SELECT ws.item_id, ws.episode_key, i.kind, i.title, i.year, e.season_number, e.number AS episode_number,
               e.title AS episode_title, ws.position_ms, ws.duration_ms, ws.completed, ws.last_watched_at, ws.last_media_id
        FROM watch_state ws JOIN library_item i ON i.id = ws.item_id LEFT JOIN episode e ON e.id = ws.episode_key
        WHERE i.hidden = 0 ORDER BY ws.last_watched_at DESC""",
    )
    fun recentlyWatched(): PagingSource<Int, WatchEntryRow>

    // ---- history log ----

    @Query("SELECT * FROM watch_history WHERE item_id = :itemId AND episode_key = :episodeKey ORDER BY watched_at DESC LIMIT 1")
    suspend fun lastSession(itemId: Long, episodeKey: Long): WatchHistoryEntity?

    @Insert
    suspend fun insertHistory(entry: WatchHistoryEntity): Long

    @Update
    suspend fun updateHistory(entry: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history ORDER BY watched_at DESC")
    fun history(): PagingSource<Int, WatchHistoryEntity>

    @Query("DELETE FROM watch_history WHERE id NOT IN (SELECT id FROM watch_history ORDER BY watched_at DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int)

    @Query("UPDATE watch_history SET item_id = :target WHERE item_id = :source")
    suspend fun moveHistory(source: Long, target: Long)

    @Query("UPDATE watch_history SET item_id = :target, episode_key = :targetEpisode WHERE item_id = :source AND episode_key = :sourceEpisode")
    suspend fun moveEpisodeHistory(source: Long, sourceEpisode: Long, target: Long, targetEpisode: Long)

    @Query("DELETE FROM watch_history WHERE item_id = :itemId AND episode_key = :episodeKey")
    suspend fun clearHistory(itemId: Long, episodeKey: Long)

    /**
     * Every viewing sitting since [since], with the title's kind, audio languages and genres (read
     * from the library and metadata tables, not the lazily refreshed search index), oldest first.
     * For local watch statistics.
     */
    @Query(
        """SELECT h.item_id, h.episode_key, h.started_at, h.watched_at, h.duration_ms, h.completed,
        i.kind AS kind,
        IFNULL((SELECT GROUP_CONCAT(l.language) FROM library_item_language l WHERE l.item_id = h.item_id), '') AS languages,
        IFNULL((SELECT m.genres FROM item_metadata m WHERE m.item_id = h.item_id), '') AS genres
        FROM watch_history h LEFT JOIN library_item i ON i.id = h.item_id
        WHERE h.watched_at >= :since ORDER BY h.started_at""",
    )
    fun observeSessions(since: Long): Flow<List<StatsSessionRow>>
}

data class StatsSessionRow(
    @androidx.room.ColumnInfo(name = "item_id") val itemId: Long,
    @androidx.room.ColumnInfo(name = "episode_key") val episodeKey: Long,
    @androidx.room.ColumnInfo(name = "started_at") val startedAt: Long,
    @androidx.room.ColumnInfo(name = "watched_at") val watchedAt: Long,
    @androidx.room.ColumnInfo(name = "duration_ms") val durationMs: Long,
    val completed: Boolean,
    val kind: String?,
    val languages: String,
    val genres: String,
)
