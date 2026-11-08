package dev.yourstream.catalog.sync

import dev.yourstream.catalog.core.CatalogCache
import dev.yourstream.catalog.core.CatalogCacheEntry
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.CatalogCacheEntity
import javax.inject.Inject
import javax.inject.Singleton

/** The catalog manager's cache, persisted: catalogs open offline (stale) and survive restarts. */
@Singleton
class RoomCatalogCache @Inject constructor(db: YourStreamDatabase) : CatalogCache {
    private val dao = db.catalogDao()

    override suspend fun get(provider: String, key: String): CatalogCacheEntry? =
        dao.cacheEntry(provider, key)?.let { CatalogCacheEntry(it.payload, it.fetchedAt, it.expiresAt) }

    override suspend fun put(provider: String, key: String, entry: CatalogCacheEntry) =
        dao.putCache(CatalogCacheEntity(provider, key, entry.payload, entry.fetchedAt, entry.expiresAt))

    override suspend fun purge(olderThan: Long) {
        dao.purgeCache(olderThan)
    }
}
