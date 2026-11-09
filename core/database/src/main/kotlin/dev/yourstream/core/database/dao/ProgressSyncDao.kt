package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads for the optional multi-device progress sync. Local ids differ between devices, so watch
 * state is exported with identities every device derives the same way from the same Telegram
 * files: the file's unique id, and the title's match key plus season/episode numbers.
 */
@Dao
interface ProgressSyncDao {
    @Query(
        """SELECT ws.item_id, ws.episode_key, ws.position_ms, ws.duration_ms, ws.completed, ws.play_count, ws.last_watched_at,
        i.match_key, e.season_number, e.number AS episode_number, m.file_unique_id
        FROM watch_state ws JOIN library_item i ON i.id = ws.item_id
        LEFT JOIN episode e ON e.id = ws.episode_key AND ws.episode_key != 0
        LEFT JOIN media m ON m.id = ws.last_media_id
        WHERE ws.last_watched_at >= :since""",
    )
    suspend fun exportStates(since: Long): List<SyncStateRow>

    /** The library unit a Telegram file belongs to on this device. */
    @Query(
        """SELECT l.item_id AS item_id, IFNULL(l.episode_id, 0) AS episode_key, m.id AS media_id
        FROM media m JOIN media_link l ON l.media_id = m.id
        WHERE m.file_unique_id = :uniqueId AND m.is_deleted = 0 AND l.method != 'EXCLUDED' LIMIT 1""",
    )
    suspend fun unitByFile(uniqueId: String): SyncUnitRow?

    @Query("SELECT id FROM library_item WHERE match_key = :matchKey LIMIT 1")
    suspend fun itemByKey(matchKey: String): Long?

    @Query("SELECT id FROM episode WHERE series_id = :seriesId AND season_number = :season AND number = :number LIMIT 1")
    suspend fun episodeByNumber(seriesId: Long, season: Int, number: Int): Long?

    /** Changes whenever any progress is saved (drives the automatic sync). */
    @Query("SELECT MAX(last_watched_at) FROM watch_state")
    fun observeLatestActivity(): Flow<Long?>
}

data class SyncStateRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "episode_key") val episodeKey: Long,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val completed: Boolean,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    @ColumnInfo(name = "match_key") val matchKey: String,
    @ColumnInfo(name = "season_number") val seasonNumber: Int?,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int?,
    @ColumnInfo(name = "file_unique_id") val fileUniqueId: String?,
)

data class SyncUnitRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "episode_key") val episodeKey: Long,
    @ColumnInfo(name = "media_id") val mediaId: Long,
)
