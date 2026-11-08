package dev.yourstream.metadata.core

import dev.yourstream.metadata.api.ProviderId
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent response cache, keyed by provider + request key. [payload] null = a cached
 * "not found" (negative entry), so missing titles are not asked for again and again.
 */
data class CacheEntry(val payload: String?, val fetchedAt: Long, val expiresAt: Long) {
    fun isFresh(now: Long): Boolean = now < expiresAt
}

interface MetadataCache {
    suspend fun get(provider: ProviderId, key: String): CacheEntry?
    suspend fun put(provider: ProviderId, key: String, entry: CacheEntry)
    suspend fun purgeExpired(now: Long, keepStaleForMillis: Long)
}

class InMemoryMetadataCache : MetadataCache {
    private val map = ConcurrentHashMap<String, CacheEntry>()
    override suspend fun get(provider: ProviderId, key: String) = map["$provider|$key"]
    override suspend fun put(provider: ProviderId, key: String, entry: CacheEntry) { map["$provider|$key"] = entry }
    override suspend fun purgeExpired(now: Long, keepStaleForMillis: Long) {
        map.entries.removeIf { it.value.expiresAt + keepStaleForMillis < now }
    }
    val size: Int get() = map.size
}

/** How long answers stay fresh. Stale entries are still served when every provider is failing. */
data class CachePolicy(
    val searchTtlMillis: Long = 7 * DAY,
    val detailsTtlMillis: Long = 30 * DAY,
    val episodeTtlMillis: Long = 14 * DAY,
    /** "Not found" answers expire sooner: the provider may add the title later. */
    val notFoundTtlMillis: Long = 2 * DAY,
) {
    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
