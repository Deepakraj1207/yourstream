package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.yourstream.core.database.entity.WatchDismissalEntity
import kotlinx.coroutines.flow.Flow

/** An item with its own (episode_key = 0) watch unit and user flags. */
data class WatchItemRow(
    val id: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    val hidden: Boolean,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
    @ColumnInfo(name = "favorite_at") val favoriteAt: Long?,
    @ColumnInfo(name = "dismissed_at") val dismissedAt: Long?,
    /** Latest activity over all units of the item. */
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long?,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val completed: Boolean,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_media_id") val lastMediaId: Long?,
    /** Viewing sessions recorded in the history log. */
    val sessions: Int,
)

data class WatchEpisodeRow(
    val id: Long,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    val number: Int,
    @ColumnInfo(name = "number_end") val numberEnd: Int?,
    val title: String?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val completed: Boolean,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long?,
    @ColumnInfo(name = "last_media_id") val lastMediaId: Long?,
)

/** Reads for watch intelligence (continue watching, next episode, recents, favorites, abandoned). */
@Dao
interface WatchIntelligenceDao {
    /** Emits whenever watch data, favorites, dismissals, items or episodes change. */
    @Query(
        """SELECT (SELECT COUNT(*) FROM watch_state) + (SELECT COUNT(*) FROM favorite) + (SELECT COUNT(*) FROM watch_dismissal)
            + (SELECT COUNT(*) FROM library_item) + (SELECT COUNT(*) FROM episode) + (SELECT COUNT(*) FROM watch_history)""",
    )
    fun changes(): Flow<Long>

    /** Items with any watch activity, most recent first. */
    @Query("SELECT item_id FROM watch_state GROUP BY item_id ORDER BY MAX(last_watched_at) DESC LIMIT :limit")
    suspend fun watchedItemIds(limit: Int): List<Long>

    @Query("SELECT item_id FROM favorite ORDER BY added_at DESC")
    suspend fun favoriteIds(): List<Long>

    @Query(
        """SELECT i.id, i.kind, i.title, i.year, i.hidden, i.available_count, i.added_at, i.latest_added_at,
            f.added_at AS favorite_at, d.dismissed_at,
            (SELECT MAX(w.last_watched_at) FROM watch_state w WHERE w.item_id = i.id) AS last_watched_at,
            ws.position_ms, ws.duration_ms, IFNULL(ws.completed, 0) AS completed, IFNULL(ws.play_count, 0) AS play_count, ws.last_media_id,
            (SELECT COUNT(*) FROM watch_history h WHERE h.item_id = i.id) AS sessions
        FROM library_item i
        LEFT JOIN watch_state ws ON ws.item_id = i.id AND ws.episode_key = 0
        LEFT JOIN favorite f ON f.item_id = i.id
        LEFT JOIN watch_dismissal d ON d.item_id = i.id
        WHERE i.id IN (:ids)""",
    )
    suspend fun items(ids: List<Long>): List<WatchItemRow>

    @Query(
        """SELECT e.id, e.series_id, e.season_number, e.number, e.number_end, e.title, e.available_count, e.latest_added_at,
            ws.position_ms, ws.duration_ms, IFNULL(ws.completed, 0) AS completed, IFNULL(ws.play_count, 0) AS play_count,
            ws.last_watched_at, ws.last_media_id
        FROM episode e LEFT JOIN watch_state ws ON ws.item_id = e.series_id AND ws.episode_key = e.id
        WHERE e.series_id IN (:seriesIds)""",
    )
    suspend fun episodes(seriesIds: List<Long>): List<WatchEpisodeRow>

    /** Newest arrivals (an item counts as new again when new files/episodes arrive). */
    @Query(
        """SELECT id FROM library_item WHERE hidden = 0 AND available_count > 0 AND (:allKinds OR kind IN (:kinds))
        ORDER BY IFNULL(latest_added_at, added_at) DESC, id DESC LIMIT :limit""",
    )
    suspend fun recentlyAddedIds(limit: Int, allKinds: Boolean, kinds: List<String>): List<Long>

    @Upsert
    suspend fun dismiss(row: WatchDismissalEntity)

    @Query("DELETE FROM watch_dismissal WHERE item_id = :itemId")
    suspend fun restore(itemId: Long)
}
