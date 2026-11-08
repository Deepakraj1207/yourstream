package dev.yourstream.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadata schema (v4).
 *
 *   metadata_cache            raw provider answers (incl. "not found"), with expiry
 *   metadata_match            per library item: decision, provider + external id, confidence, candidates
 *   item_metadata             the applied metadata of a matched item (poster, overview, genres, cast…)
 *   season_metadata / episode_metadata
 *
 * MANUAL and REJECTED matches are user decisions; the item is locked when they are made, so
 * library cleanup never deletes it.
 */

@Entity(tableName = "metadata_cache", primaryKeys = ["provider", "cache_key"], indices = [Index(value = ["expires_at"])])
data class MetadataCacheEntity(
    val provider: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    /** JSON of the provider answer; NULL = the provider said "not found". */
    val payload: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)

@Entity(
    tableName = "metadata_match",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["status", "next_attempt_at"]), Index(value = ["provider", "external_id"])],
)
data class MetadataMatchEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long,
    /** ACCEPTED, NEEDS_REVIEW, NO_MATCH, FAILED, MANUAL, REJECTED. */
    val status: String,
    val provider: String?,
    @ColumnInfo(name = "external_id") val externalId: String?,
    /** MOVIE or SERIES. */
    @ColumnInfo(name = "external_type") val externalType: String?,
    @ColumnInfo(name = "imdb_id") val imdbId: String?,
    /** 0..1 confidence of the chosen (or best suggested) candidate. */
    val confidence: Double,
    /** JSON list of the top scored candidates with their score breakdown. */
    val candidates: String,
    /** kind|title|year the match was computed for; a change triggers a re-match. */
    @ColumnInfo(name = "input_signature") val inputSignature: String,
    val attempts: Int,
    @ColumnInfo(name = "matched_at") val matchedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

@Entity(
    tableName = "item_metadata",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["imdb_id"])],
)
data class ItemMetadataEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long,
    val provider: String,
    @ColumnInfo(name = "external_id") val externalId: String,
    @ColumnInfo(name = "imdb_id") val imdbId: String?,
    val title: String,
    @ColumnInfo(name = "original_title") val originalTitle: String?,
    val overview: String?,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String?,
    /** Comma-separated. */
    val genres: String,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    val year: Int?,
    @ColumnInfo(name = "original_language") val originalLanguage: String?,
    @ColumnInfo(name = "runtime_minutes") val runtimeMinutes: Int?,
    val rating: Double?,
    /** JSON list of cast members. */
    val cast: String,
    val directors: String,
    @ColumnInfo(name = "series_status") val seriesStatus: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

@Entity(
    tableName = "season_metadata",
    foreignKeys = [ForeignKey(SeasonEntity::class, ["id"], ["season_id"], onDelete = ForeignKey.CASCADE)],
)
data class SeasonMetadataEntity(
    @PrimaryKey @ColumnInfo(name = "season_id") val seasonId: Long,
    val name: String?,
    val overview: String?,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "air_date") val airDate: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

@Entity(
    tableName = "episode_metadata",
    foreignKeys = [ForeignKey(EpisodeEntity::class, ["id"], ["episode_id"], onDelete = ForeignKey.CASCADE)],
)
data class EpisodeMetadataEntity(
    @PrimaryKey @ColumnInfo(name = "episode_id") val episodeId: Long,
    val title: String?,
    val overview: String?,
    @ColumnInfo(name = "still_url") val stillUrl: String?,
    @ColumnInfo(name = "air_date") val airDate: String?,
    @ColumnInfo(name = "runtime_minutes") val runtimeMinutes: Int?,
    val rating: Double?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
