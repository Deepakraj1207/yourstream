package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.yourstream.core.database.entity.EpisodeMetadataEntity
import dev.yourstream.core.database.entity.ItemMetadataEntity
import dev.yourstream.core.database.entity.MetadataCacheEntity
import dev.yourstream.core.database.entity.MetadataMatchEntity
import dev.yourstream.core.database.entity.SeasonMetadataEntity
import kotlinx.coroutines.flow.Flow

/** A library item waiting for (re-)matching. */
data class MatchWorkRow(
    val id: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    @ColumnInfo(name = "title_confidence") val titleConfidence: Float,
    val status: String?,
    val attempts: Int?,
)

data class ReviewRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    val title: String,
    val year: Int?,
    val kind: String,
    val confidence: Double,
    val candidates: String,
)

data class MatchStatusCount(val status: String, val count: Int)

@Dao
interface MetadataDao {

    // ---- cache ----

    @Query("SELECT * FROM metadata_cache WHERE provider = :provider AND cache_key = :key")
    suspend fun cacheEntry(provider: String, key: String): MetadataCacheEntity?

    @Upsert
    suspend fun putCache(entry: MetadataCacheEntity)

    @Query("DELETE FROM metadata_cache WHERE expires_at < :before")
    suspend fun purgeCache(before: Long): Int

    @Query("SELECT COUNT(*) FROM metadata_cache")
    suspend fun cacheSize(): Int

    // ---- work queue ----

    /**
     * Movies/series with files that need matching: never matched, inputs changed since the last
     * match (title/year edited, better parse), or a retry is due. User decisions (MANUAL,
     * REJECTED) are never revisited automatically. Newest items first.
     */
    @Query(
        """SELECT i.id, i.kind, i.title, i.year, i.title_confidence, m.status, m.attempts
        FROM library_item i LEFT JOIN metadata_match m ON m.item_id = i.id
        WHERE i.kind IN ('MOVIE', 'SERIES') AND i.hidden = 0 AND i.available_count > 0
          AND (m.item_id IS NULL
               OR (m.status NOT IN ('MANUAL', 'REJECTED')
                   AND (m.input_signature != (i.kind || '|' || i.title || '|' || IFNULL(i.year, ''))
                        OR (m.status IN ('NO_MATCH', 'FAILED', 'NEEDS_REVIEW') AND m.next_attempt_at <= :now))))
        ORDER BY i.latest_added_at DESC, i.id DESC LIMIT :limit""",
    )
    suspend fun matchWork(now: Long, limit: Int): List<MatchWorkRow>

    // ---- matches ----

    @Upsert
    suspend fun putMatch(match: MetadataMatchEntity)

    @Query("SELECT * FROM metadata_match WHERE item_id = :itemId")
    suspend fun match(itemId: Long): MetadataMatchEntity?

    @Query("SELECT * FROM metadata_match WHERE item_id = :itemId")
    fun observeMatch(itemId: Long): Flow<MetadataMatchEntity?>

    @Query(
        """SELECT m.item_id, i.title, i.year, i.kind, m.confidence, m.candidates
        FROM metadata_match m JOIN library_item i ON i.id = m.item_id
        WHERE m.status = 'NEEDS_REVIEW' AND i.hidden = 0 ORDER BY m.confidence DESC""",
    )
    fun reviewQueue(): Flow<List<ReviewRow>>

    @Query("SELECT status, COUNT(*) AS count FROM metadata_match GROUP BY status")
    fun statusCounts(): Flow<List<MatchStatusCount>>

    // ---- applied metadata ----

    @Upsert
    suspend fun putItemMetadata(metadata: ItemMetadataEntity)

    @Query("SELECT * FROM item_metadata WHERE item_id = :itemId")
    suspend fun itemMetadata(itemId: Long): ItemMetadataEntity?

    @Query("SELECT * FROM item_metadata WHERE item_id = :itemId")
    fun observeItemMetadata(itemId: Long): Flow<ItemMetadataEntity?>

    @Query("DELETE FROM item_metadata WHERE item_id = :itemId")
    suspend fun deleteItemMetadata(itemId: Long)

    @Query("SELECT item_id FROM item_metadata WHERE fetched_at < :before LIMIT :limit")
    suspend fun staleItemMetadata(before: Long, limit: Int): List<Long>

    @Upsert
    suspend fun putSeasonMetadata(metadata: SeasonMetadataEntity)

    @Upsert
    suspend fun putEpisodeMetadata(metadata: List<EpisodeMetadataEntity>)

    @Query("SELECT * FROM episode_metadata WHERE episode_id = :episodeId")
    suspend fun episodeMetadata(episodeId: Long): EpisodeMetadataEntity?

    @Query("SELECT * FROM season_metadata WHERE season_id = :seasonId")
    suspend fun seasonMetadata(seasonId: Long): SeasonMetadataEntity?

    /** Makes the official titles searchable through the library FTS. */
    @Query("UPDATE library_item SET search_text = :searchText WHERE id = :itemId")
    suspend fun setItemSearchText(itemId: Long, searchText: String)

    @Query("UPDATE library_item SET locked = 1 WHERE id = :itemId")
    suspend fun lockItem(itemId: Long)
}
