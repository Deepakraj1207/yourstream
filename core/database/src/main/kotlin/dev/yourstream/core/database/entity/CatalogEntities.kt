package dev.yourstream.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Catalog schema (v5).
 *
 *   catalog_cache   raw catalog provider answers (manifests, pages, metadata, searches,
 *                   incl. "not found"), with expiry. Expired rows are kept for a while and
 *                   served as stale results when a provider is down.
 *
 * Catalog rows never reference library rows: a catalog title is not a media source.
 */
@Entity(tableName = "catalog_cache", primaryKeys = ["provider", "cache_key"], indices = [Index(value = ["expires_at"])])
data class CatalogCacheEntity(
    val provider: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    /** JSON of the provider answer; NULL = the provider said "not found". */
    val payload: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)
