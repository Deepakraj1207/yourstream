package dev.yourstream.metadata.api

import dev.yourstream.core.common.AppResult

/**
 * One external metadata source. Implementations only translate HTTP ↔ these models: caching,
 * rate-limit cool-downs, fallback and matching are done above them by the provider manager,
 * so a new provider is a single class.
 *
 * Errors use [dev.yourstream.core.common.AppError]: NotFound (no such title), RateLimited
 * (429, with Retry-After), NotConfigured (missing/rejected key), Network/Timeout/Remote
 * (transient). Implementations must not throw for these.
 */
interface MetadataProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: Set<ProviderCapability>

    /** False when required configuration (e.g. an API key) is missing; the manager skips it. */
    val isConfigured: Boolean

    /** Candidates for a title (and year when given), best first as the provider ranks them. */
    suspend fun search(query: MetadataQuery): AppResult<List<MetadataCandidate>>

    suspend fun movie(id: String, language: String? = null): AppResult<MovieMetadata>
    suspend fun series(id: String, language: String? = null): AppResult<SeriesMetadata>
    suspend fun season(seriesId: String, seasonNumber: Int, language: String? = null): AppResult<SeasonMetadata>
    suspend fun episode(seriesId: String, seasonNumber: Int, episodeNumber: Int, language: String? = null): AppResult<EpisodeMetadata>

    /** This provider's ref for an IMDb id, or NotFound. Only with [ProviderCapability.FIND_BY_IMDB_ID]. */
    suspend fun findByImdbId(imdbId: String, type: MetadataType): AppResult<MetadataRef>

    fun supports(capability: ProviderCapability): Boolean = capability in capabilities
}
