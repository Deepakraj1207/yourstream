package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.yourstream.core.database.entity.CatalogCacheEntity

/** A library title (with applied metadata, if matched) seen as a catalog entry. */
data class LocalCatalogRow(
    val id: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    /** Comma-separated audio languages of the item's files. */
    val languages: String?,
    @ColumnInfo(name = "meta_provider") val metaProvider: String?,
    @ColumnInfo(name = "external_id") val externalId: String?,
    @ColumnInfo(name = "imdb_id") val imdbId: String?,
    @ColumnInfo(name = "meta_title") val metaTitle: String?,
    val overview: String?,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String?,
    val genres: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    @ColumnInfo(name = "original_language") val originalLanguage: String?,
    @ColumnInfo(name = "runtime_minutes") val runtimeMinutes: Int?,
    val rating: Double?,
    val cast: String?,
    val directors: String?,
)

/** A library item found for a catalog title, and the key it was found by. */
data class PresenceRow(
    @ColumnInfo(name = "matched_key") val key: String,
    val id: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
)

private const val LOCAL_SELECT = """SELECT i.id, i.kind, i.title, i.year, i.available_count,
        (SELECT group_concat(l.language) FROM library_item_language l WHERE l.item_id = i.id) AS languages,
        md.provider AS meta_provider, md.external_id, md.imdb_id, md.title AS meta_title, md.overview, md.poster_url,
        md.backdrop_url, md.genres, md.release_date, md.original_language, md.runtime_minutes, md.rating, md.cast, md.directors
    FROM library_item i LEFT JOIN item_metadata md ON md.item_id = i.id"""

private const val VISIBLE = "i.hidden = 0 AND i.available_count > 0"

@Dao
interface CatalogDao {

    // ---- cache ----

    @Query("SELECT * FROM catalog_cache WHERE provider = :provider AND cache_key = :key")
    suspend fun cacheEntry(provider: String, key: String): CatalogCacheEntity?

    @Upsert
    suspend fun putCache(entry: CatalogCacheEntity)

    @Query("DELETE FROM catalog_cache WHERE expires_at < :before")
    suspend fun purgeCache(before: Long): Int

    @Query("SELECT COUNT(*) FROM catalog_cache")
    suspend fun cacheSize(): Int

    // ---- the library as a catalog ----

    /** Watchable movies/series, newest first; [language] matches file audio or the original language. */
    @Query(
        """$LOCAL_SELECT
        WHERE i.kind IN (:kinds) AND $VISIBLE
          AND (:language IS NULL
               OR EXISTS (SELECT 1 FROM library_item_language l WHERE l.item_id = i.id AND l.language = :language)
               OR md.original_language = :language)
        ORDER BY i.latest_added_at DESC, i.id DESC LIMIT :limit OFFSET :skip""",
    )
    suspend fun localItems(kinds: List<String>, language: String?, skip: Int, limit: Int): List<LocalCatalogRow>

    /** [match] is an FTS5 query over title + search text (which includes official titles). */
    @Query(
        """$LOCAL_SELECT JOIN library_item_fts ON library_item_fts.rowid = i.id
        WHERE library_item_fts MATCH :match AND i.kind IN (:kinds) AND $VISIBLE
        ORDER BY i.available_count DESC, i.id DESC LIMIT :limit""",
    )
    suspend fun searchLocal(match: String, kinds: List<String>, limit: Int): List<LocalCatalogRow>

    @Query("$LOCAL_SELECT WHERE i.id = :itemId")
    suspend fun localItem(itemId: Long): LocalCatalogRow?

    // ---- presence of catalog titles in the library ----

    /** By IMDb id, from applied metadata or an accepted/confirmed match. */
    @Query(
        """SELECT k.imdb_id AS matched_key, i.id, i.kind, i.title, i.year, i.available_count FROM (
            SELECT item_id, imdb_id FROM item_metadata WHERE imdb_id IN (:imdbIds)
            UNION SELECT item_id, imdb_id FROM metadata_match WHERE imdb_id IN (:imdbIds) AND status IN ('ACCEPTED', 'MANUAL')
        ) k JOIN library_item i ON i.id = k.item_id WHERE $VISIBLE""",
    )
    suspend fun presenceByImdb(imdbIds: List<String>): List<PresenceRow>

    @Query(
        """SELECT k.external_id AS matched_key, i.id, i.kind, i.title, i.year, i.available_count FROM (
            SELECT item_id, external_id FROM item_metadata WHERE provider = :provider AND external_id IN (:ids)
            UNION SELECT item_id, external_id FROM metadata_match WHERE provider = :provider AND external_id IN (:ids) AND status IN ('ACCEPTED', 'MANUAL')
        ) k JOIN library_item i ON i.id = k.item_id WHERE $VISIBLE""",
    )
    suspend fun presenceByExternalId(provider: String, ids: List<String>): List<PresenceRow>

    /** By normalized title (the library's grouping key). The caller checks year and kind. */
    @Query(
        """SELECT i.normalized_title AS matched_key, i.id, i.kind, i.title, i.year, i.available_count
        FROM library_item i WHERE i.normalized_title IN (:keys) AND i.kind IN ('MOVIE', 'SERIES') AND $VISIBLE""",
    )
    suspend fun presenceByTitle(keys: List<String>): List<PresenceRow>
}
