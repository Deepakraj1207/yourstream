package dev.yourstream.catalog.core

import java.util.concurrent.ConcurrentHashMap

data class CatalogCacheEntry(val payload: String?, val fetchedAt: Long, val expiresAt: Long) {
    fun isFresh(now: Long) = now < expiresAt
}

/** Persistent cache of provider answers, keyed by provider id + request key. */
interface CatalogCache {
    suspend fun get(provider: String, key: String): CatalogCacheEntry?
    suspend fun put(provider: String, key: String, entry: CatalogCacheEntry)
    suspend fun purge(olderThan: Long)
}

class InMemoryCatalogCache : CatalogCache {
    private val map = ConcurrentHashMap<String, CatalogCacheEntry>()
    override suspend fun get(provider: String, key: String) = map["$provider|$key"]
    override suspend fun put(provider: String, key: String, entry: CatalogCacheEntry) { map["$provider|$key"] = entry }
    override suspend fun purge(olderThan: Long) { map.entries.removeIf { it.value.expiresAt < olderThan } }
}

data class CatalogCachePolicy(
    /** Manifests change rarely; re-checked daily (availability is also re-checked on demand). */
    val manifestTtlMillis: Long = 24 * HOUR,
    /** Catalog pages: the add-ons refresh every 4 h (IndiaStreams) to daily (Indian Regional). */
    val pageTtlMillis: Long = 4 * HOUR,
    val metadataTtlMillis: Long = 7 * 24 * HOUR,
    val searchTtlMillis: Long = 24 * HOUR,
    val notFoundTtlMillis: Long = 24 * HOUR,
) {
    companion object { const val HOUR = 60 * 60 * 1000L }
}
