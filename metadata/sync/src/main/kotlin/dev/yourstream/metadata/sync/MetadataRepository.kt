package dev.yourstream.metadata.sync

import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.ItemMetadataEntity
import dev.yourstream.core.database.entity.MetadataMatchEntity
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.ProviderStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read side: what is known about items, what waits for review, how providers are doing. */
@Singleton
class MetadataRepository @Inject constructor(
    db: YourStreamDatabase,
    private val service: LibraryMetadataService,
    private val manager: MetadataProviderManager,
) {
    private val dao = db.metadataDao()

    fun itemMetadata(itemId: Long): Flow<ItemMetadata?> = dao.observeItemMetadata(itemId).map { it?.toModel() }
    fun match(itemId: Long): Flow<ItemMatch?> = dao.observeMatch(itemId).map { it?.toModel() }
    fun reviewQueue(): Flow<List<ReviewItem>> = dao.reviewQueue().map { rows ->
        rows.map { ReviewItem(it.itemId, it.title, it.year, it.kind, it.confidence, service.decodeCandidates(it.candidates)) }
    }
    fun statusCounts(): Flow<Map<String, Int>> = dao.statusCounts().map { list -> list.associate { it.status to it.count } }
    fun providerStatus(): List<ProviderStatus> = manager.status()
    fun configuredProviders(): List<String> = manager.providers.filter { it.isConfigured }.map { it.displayName }

    suspend fun episodeMetadata(episodeId: Long) = dao.episodeMetadata(episodeId)

    private fun ItemMetadataEntity.toModel() = ItemMetadata(
        itemId, provider, externalId, imdbId, title, originalTitle, overview, posterUrl, backdropUrl,
        genres.split(',').filter { it.isNotBlank() }, releaseDate, year, originalLanguage, runtimeMinutes, rating,
        service.decodeCast(cast), directors.split(',').filter { it.isNotBlank() }, fetchedAt,
    )

    private fun MetadataMatchEntity.toModel() = ItemMatch(
        itemId, runCatching { MatchStatus.valueOf(status) }.getOrDefault(MatchStatus.NO_MATCH), provider, externalId, imdbId,
        confidence, service.decodeCandidates(candidates), attempts, matchedAt, updatedAt, nextAttemptAt, lastError,
    )
}
