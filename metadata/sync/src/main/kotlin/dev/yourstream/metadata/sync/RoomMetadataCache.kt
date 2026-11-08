package dev.yourstream.metadata.sync

import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.MetadataCacheEntity
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.metadata.core.CacheEntry
import dev.yourstream.metadata.core.MetadataCache
import javax.inject.Inject
import javax.inject.Singleton

/** The manager's cache, persisted: survives restarts, so the same title is never fetched twice. */
@Singleton
class RoomMetadataCache @Inject constructor(db: YourStreamDatabase) : MetadataCache {
    private val dao = db.metadataDao()

    override suspend fun get(provider: ProviderId, key: String): CacheEntry? =
        dao.cacheEntry(provider.value, key)?.let { CacheEntry(it.payload, it.fetchedAt, it.expiresAt) }

    override suspend fun put(provider: ProviderId, key: String, entry: CacheEntry) =
        dao.putCache(MetadataCacheEntity(provider.value, key, entry.payload, entry.fetchedAt, entry.expiresAt))

    override suspend fun purgeExpired(now: Long, keepStaleForMillis: Long) {
        dao.purgeCache(now - keepStaleForMillis)
    }
}
