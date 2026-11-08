package dev.yourstream.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import dev.yourstream.core.database.entity.EpisodeEntity
import dev.yourstream.core.database.entity.FavoriteEntity
import dev.yourstream.core.database.entity.ItemMetadataEntity
import dev.yourstream.core.database.entity.LibraryAliasEntity
import dev.yourstream.core.database.entity.LibraryItemEntity
import dev.yourstream.core.database.entity.LibraryItemLanguageEntity
import dev.yourstream.core.database.entity.MediaLinkEntity
import dev.yourstream.core.database.entity.SeasonEntity
import dev.yourstream.core.database.entity.WatchStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // ================================================================ sync: pending queue

    @Query("SELECT media_id FROM library_pending ORDER BY media_id LIMIT :limit")
    suspend fun pendingMedia(limit: Int): List<Long>

    @Query("DELETE FROM library_pending WHERE media_id IN (:ids)")
    suspend fun clearPending(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM library_pending")
    suspend fun pendingCount(): Int

    @Query("SELECT item_id FROM library_dirty_item LIMIT :limit")
    suspend fun dirtyItems(limit: Int): List<Long>

    @Query("DELETE FROM library_dirty_item WHERE item_id IN (:ids)")
    suspend fun clearDirty(ids: List<Long>)

    @Query(
        """SELECT m.id AS media_id, m.chat_id, m.message_id, m.media_type, m.file_name, m.is_deleted, m.canonical_media_id,
               m.indexed_at, m.extension, p.title, p.normalized_title, p.alternative_title, p.year, p.season, p.episode,
               p.episode_end, p.absolute_episode, p.air_date, p.kind, p.confidence, p.field_confidence, p.languages,
               p.subtitle_languages, p.audio_codecs, p.audio_channels, p.parser_version,
               l.item_id AS link_item_id, l.episode_id AS link_episode_id, l.method AS link_method, l.signature AS link_signature
        FROM media m
        LEFT JOIN media_parsed p ON p.media_id = m.id
        LEFT JOIN media_link l ON l.media_id = m.id
        WHERE m.id IN (:mediaIds)""",
    )
    suspend fun linkInputs(mediaIds: List<Long>): List<LinkInputRow>

    // ================================================================ items

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: LibraryItemEntity): Long

    @Update
    suspend fun updateItem(item: LibraryItemEntity)

    @Query("SELECT * FROM library_item WHERE id = :id")
    suspend fun item(id: Long): LibraryItemEntity?

    @Query("SELECT * FROM library_item WHERE id = :id")
    fun observeItem(id: Long): Flow<LibraryItemEntity?>

    @Query("SELECT id FROM library_item WHERE match_key = :key")
    suspend fun itemIdByKey(key: String): Long?

    @Query("SELECT item_id FROM library_alias WHERE match_key = :key")
    suspend fun aliasTarget(key: String): Long?

    @Upsert
    suspend fun upsertAlias(alias: LibraryAliasEntity)

    @Query("UPDATE library_alias SET item_id = :target WHERE item_id = :source")
    suspend fun repointAliases(source: Long, target: Long)

    /** Dated movies sharing a title key, to attach a year-less file when there is exactly one. */
    @Query("SELECT id FROM library_item WHERE kind = 'MOVIE' AND normalized_title = :key AND hidden = 0 LIMIT 2")
    suspend fun moviesByTitle(key: String): List<Long>

    @Query("SELECT id FROM library_item WHERE kind = 'SERIES' AND normalized_title = :key LIMIT 1")
    suspend fun seriesByTitle(key: String): Long?

    @Query("DELETE FROM library_item WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("UPDATE library_item SET hidden = :hidden, locked = 1, updated_at = :now WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean, now: Long)

    @Query("DELETE FROM library_item_language WHERE item_id = :itemId")
    suspend fun clearLanguages(itemId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLanguages(languages: List<LibraryItemLanguageEntity>)

    @Query("SELECT language FROM library_item_language WHERE item_id = :itemId ORDER BY language")
    suspend fun languages(itemId: Long): List<String>

    /** Aggregates over live, non-excluded files of an item. */
    @Query(
        """SELECT COUNT(*) AS available_count, MAX(m.indexed_at) AS latest_added_at
        FROM media_link l JOIN media m ON m.id = l.media_id
        WHERE l.item_id = :itemId AND l.method != 'EXCLUDED' AND m.is_deleted = 0""",
    )
    suspend fun itemCounts(itemId: Long): SyncCountsRow

    @Query(
        """SELECT MAX(p.resolution) FROM media_link l JOIN media m ON m.id = l.media_id JOIN media_parsed p ON p.media_id = m.id
        WHERE l.item_id = :itemId AND l.method != 'EXCLUDED' AND m.is_deleted = 0""",
    )
    suspend fun itemMaxResolution(itemId: Long): Int?

    @Query(
        """SELECT COALESCE(SUM(m.size_bytes), 0) FROM media_link l JOIN media m ON m.id = l.media_id
        WHERE l.item_id = :itemId AND l.method != 'EXCLUDED' AND m.is_deleted = 0""",
    )
    suspend fun itemTotalSize(itemId: Long): Long

    @Query(
        """SELECT p.languages FROM media_link l JOIN media m ON m.id = l.media_id JOIN media_parsed p ON p.media_id = m.id
        WHERE l.item_id = :itemId AND l.method != 'EXCLUDED' AND m.is_deleted = 0 AND p.languages != ''""",
    )
    suspend fun itemLanguageLists(itemId: Long): List<String>

    @Query(
        """SELECT DISTINCT p.alternative_title FROM media_link l JOIN media_parsed p ON p.media_id = l.media_id
        WHERE l.item_id = :itemId AND p.alternative_title IS NOT NULL LIMIT 5""",
    )
    suspend fun itemAlternativeTitles(itemId: Long): List<String>

    @Query("SELECT COUNT(*) FROM episode WHERE series_id = :seriesId AND available_count > 0")
    suspend fun availableEpisodeCount(seriesId: Long): Int

    @Query("SELECT COUNT(*) FROM season WHERE series_id = :seriesId AND episode_count > 0")
    suspend fun availableSeasonCount(seriesId: Long): Int

    @Query(
        """UPDATE library_item SET available_count = :available, latest_added_at = :latestAddedAt, max_resolution = :maxResolution,
        total_size = :totalSize, episode_count = :episodeCount, season_count = :seasonCount, search_text = :searchText,
        updated_at = :now WHERE id = :id""",
    )
    suspend fun setItemAggregates(
        id: Long, available: Int, latestAddedAt: Long?, maxResolution: Int?, totalSize: Long,
        episodeCount: Int, seasonCount: Int, searchText: String, now: Long,
    )

    /**
     * Items nobody needs any more: no live files, no user data, not locked. Items with progress,
     * a favorite or a track preference are kept (shown as unavailable) whatever happens to files.
     */
    @Query(
        """DELETE FROM library_item WHERE available_count = 0 AND locked = 0
        AND NOT EXISTS (SELECT 1 FROM favorite f WHERE f.item_id = library_item.id)
        AND NOT EXISTS (SELECT 1 FROM watch_state w WHERE w.item_id = library_item.id)
        AND NOT EXISTS (SELECT 1 FROM track_preference t WHERE t.item_id = library_item.id)
        AND NOT EXISTS (SELECT 1 FROM media_link l JOIN media m ON m.id = l.media_id
                        WHERE l.item_id = library_item.id AND m.is_deleted = 0)
        AND id IN (:candidates)""",
    )
    suspend fun deleteOrphanItems(candidates: List<Long>): Int

    // ================================================================ seasons / episodes

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeason(season: SeasonEntity): Long

    @Query("SELECT id FROM season WHERE series_id = :seriesId AND number = :number")
    suspend fun seasonId(seriesId: Long, number: Int): Long?

    @Query("SELECT * FROM season WHERE series_id = :seriesId ORDER BY CASE WHEN number < 0 THEN 1000 ELSE number END")
    suspend fun seasons(seriesId: Long): List<SeasonEntity>

    @Query("SELECT * FROM season WHERE series_id = :seriesId ORDER BY CASE WHEN number < 0 THEN 1000 ELSE number END")
    fun observeSeasons(seriesId: Long): Flow<List<SeasonEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisode(episode: EpisodeEntity): Long

    @Query("SELECT id FROM episode WHERE season_id = :seasonId AND number = :number")
    suspend fun episodeId(seasonId: Long, number: Int): Long?

    @Query("SELECT * FROM episode WHERE id = :id")
    suspend fun episode(id: Long): EpisodeEntity?

    @Query("UPDATE episode SET number_end = COALESCE(:numberEnd, number_end), absolute_number = COALESCE(:absolute, absolute_number), air_date = COALESCE(:airDate, air_date) WHERE id = :id")
    suspend fun enrichEpisode(id: Long, numberEnd: Int?, absolute: Int?, airDate: String?)

    @Query(
        """UPDATE episode SET
          available_count = (SELECT COUNT(*) FROM media_link l JOIN media m ON m.id = l.media_id
                             WHERE l.episode_id = episode.id AND l.method != 'EXCLUDED' AND m.is_deleted = 0),
          latest_added_at = (SELECT MAX(m.indexed_at) FROM media_link l JOIN media m ON m.id = l.media_id
                             WHERE l.episode_id = episode.id AND l.method != 'EXCLUDED' AND m.is_deleted = 0)
        WHERE series_id = :seriesId""",
    )
    suspend fun recomputeEpisodes(seriesId: Long)

    @Query(
        """UPDATE season SET episode_count = (SELECT COUNT(*) FROM episode e WHERE e.season_id = season.id AND e.available_count > 0)
        WHERE series_id = :seriesId""",
    )
    suspend fun recomputeSeasons(seriesId: Long)

    /** Episodes with no files at all and no progress are removed; ones with progress stay. */
    @Query(
        """DELETE FROM episode WHERE series_id = :seriesId
        AND NOT EXISTS (SELECT 1 FROM media_link l WHERE l.episode_id = episode.id)
        AND NOT EXISTS (SELECT 1 FROM watch_state w WHERE w.item_id = episode.series_id AND w.episode_key = episode.id)""",
    )
    suspend fun deleteOrphanEpisodes(seriesId: Long)

    @Query("DELETE FROM season WHERE series_id = :seriesId AND NOT EXISTS (SELECT 1 FROM episode e WHERE e.season_id = season.id)")
    suspend fun deleteEmptySeasons(seriesId: Long)

    /** Next episode in watch order (season, then number), or null at the end. */
    @Query(
        """SELECT * FROM episode WHERE series_id = :seriesId AND available_count > 0
        AND (CASE WHEN season_number < 0 THEN 1000 ELSE season_number END > :seasonOrder
             OR (CASE WHEN season_number < 0 THEN 1000 ELSE season_number END = :seasonOrder AND number > :number))
        ORDER BY CASE WHEN season_number < 0 THEN 1000 ELSE season_number END, number LIMIT 1""",
    )
    suspend fun nextEpisode(seriesId: Long, seasonOrder: Int, number: Int): EpisodeEntity?

    @Query("SELECT id FROM episode WHERE series_id = :seriesId")
    suspend fun episodeIds(seriesId: Long): List<Long>

    @Query("SELECT id FROM episode WHERE season_id = :seasonId")
    suspend fun episodeIdsOfSeason(seasonId: Long): List<Long>

    @Query("SELECT series_id FROM season WHERE id = :seasonId")
    suspend fun seriesOfSeason(seasonId: Long): Long?

    @Query("SELECT * FROM episode WHERE series_id = :seriesId")
    suspend fun episodesOf(seriesId: Long): List<EpisodeEntity>

    @Query("UPDATE episode SET series_id = :target, season_id = :seasonId, season_number = :seasonNumber WHERE id = :episodeId")
    suspend fun moveEpisode(episodeId: Long, target: Long, seasonId: Long, seasonNumber: Int)

    @Query("DELETE FROM episode WHERE id = :id")
    suspend fun deleteEpisode(id: Long)

    @Query(
        """SELECT e.id, e.series_id, e.season_id, e.season_number, e.number, e.number_end, e.absolute_number, e.title, e.air_date,
               e.available_count, e.latest_added_at, ws.position_ms, ws.duration_ms, COALESCE(ws.completed, 0) AS completed, ws.last_watched_at
        FROM episode e LEFT JOIN watch_state ws ON ws.item_id = e.series_id AND ws.episode_key = e.id
        WHERE e.series_id = :seriesId AND (:seasonId IS NULL OR e.season_id = :seasonId)
          AND (:includeUnavailable OR e.available_count > 0)
        ORDER BY CASE WHEN e.season_number < 0 THEN 1000 ELSE e.season_number END, e.number""",
    )
    fun episodesPaged(seriesId: Long, seasonId: Long?, includeUnavailable: Boolean): PagingSource<Int, EpisodeRow>

    @Query(
        """SELECT e.id, e.series_id, e.season_id, e.season_number, e.number, e.number_end, e.absolute_number, e.title, e.air_date,
               e.available_count, e.latest_added_at, ws.position_ms, ws.duration_ms, COALESCE(ws.completed, 0) AS completed, ws.last_watched_at
        FROM episode e LEFT JOIN watch_state ws ON ws.item_id = e.series_id AND ws.episode_key = e.id
        WHERE e.id = :episodeId""",
    )
    suspend fun episodeRow(episodeId: Long): EpisodeRow?

    // ================================================================ links

    @Upsert
    suspend fun upsertLink(link: MediaLinkEntity)

    @Query("SELECT * FROM media_link WHERE media_id = :mediaId")
    suspend fun link(mediaId: Long): MediaLinkEntity?

    @Query("DELETE FROM media_link WHERE media_id = :mediaId")
    suspend fun deleteLink(mediaId: Long)

    @Query("UPDATE media_link SET signature = :signature, linked_at = :now WHERE media_id = :mediaId")
    suspend fun touchLink(mediaId: Long, signature: String, now: Long)

    @Query("UPDATE media_link SET item_id = :target WHERE item_id = :source")
    suspend fun moveLinks(source: Long, target: Long)

    @Query("UPDATE media_link SET episode_id = :target WHERE episode_id = :source")
    suspend fun moveEpisodeLinks(source: Long, target: Long)

    @Query(
        """SELECT m.id AS media_id, m.chat_id, m.message_id, m.file_name, m.size_bytes, m.duration_seconds, p.resolution,
               p.source AS quality, COALESCE(m.video_codec, p.video_codec) AS video_codec, p.languages, m.is_deleted,
               (m.canonical_media_id IS NOT NULL) AS is_duplicate, l.episode_id, l.method, m.download_state
        FROM media_link l JOIN media m ON m.id = l.media_id LEFT JOIN media_parsed p ON p.media_id = m.id
        WHERE l.item_id = :itemId AND (:episodeId IS NULL OR l.episode_id = :episodeId)
          AND (:includeUnavailable OR (m.is_deleted = 0 AND l.method != 'EXCLUDED'))
        ORDER BY m.is_deleted, COALESCE(p.resolution, 0) DESC, m.size_bytes DESC""",
    )
    suspend fun versions(itemId: Long, episodeId: Long?, includeUnavailable: Boolean): List<MediaVersionRow>

    // ================================================================ lists

    /** Filtered/sorted lists are built by LibraryQueryBuilder (whitelisted SQL, bound arguments). */
    @RawQuery(observedEntities = [LibraryItemEntity::class, FavoriteEntity::class, WatchStateEntity::class, ItemMetadataEntity::class])
    fun itemsPaged(query: SupportSQLiteQuery): PagingSource<Int, LibraryItemRow>

    @RawQuery(observedEntities = [LibraryItemEntity::class, FavoriteEntity::class, WatchStateEntity::class, ItemMetadataEntity::class])
    suspend fun items(query: SupportSQLiteQuery): List<LibraryItemRow>

    @RawQuery(observedEntities = [LibraryItemEntity::class, FavoriteEntity::class, WatchStateEntity::class, ItemMetadataEntity::class])
    fun count(query: SupportSQLiteQuery): Flow<Int>

    // ================================================================ favorites

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE item_id = :itemId")
    suspend fun removeFavorite(itemId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE item_id = :itemId)")
    suspend fun isFavorite(itemId: Long): Boolean

    @Query("UPDATE OR IGNORE favorite SET item_id = :target WHERE item_id = :source")
    suspend fun moveFavorite(source: Long, target: Long)
}
