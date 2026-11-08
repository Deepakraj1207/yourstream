package dev.yourstream.metadata.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.metadata.api.EpisodeMetadata
import dev.yourstream.metadata.api.MetadataCandidate
import dev.yourstream.metadata.api.MetadataProvider
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.MovieMetadata
import dev.yourstream.metadata.api.ProviderCapability
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.metadata.api.SeasonMetadata
import dev.yourstream.metadata.api.SeriesMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A value plus where it came from. */
data class Sourced<T>(val provider: ProviderId, val value: T, val fromCache: Boolean)

/**
 * The only entry point to metadata providers. Callers never pick a provider themselves.
 *
 *  - Order: [providers] as configured (e.g. TMDB first, Cinemeta second); providers without
 *    configuration or the needed capability are skipped.
 *  - Fallback: a search that fails or finds nothing moves on to the next provider. Details are
 *    fetched from the provider that owns the id; if it is down, another provider is asked via
 *    the IMDb id.
 *  - Caching: every answer (including "not found") is cached with a TTL; identical concurrent
 *    requests share one network call; when all providers fail, a stale cache entry is returned.
 *  - Health: rate limits and repeated failures take a provider out of rotation for a while.
 */
class MetadataProviderManager(
    providers: List<MetadataProvider>,
    private val cache: MetadataCache,
    private val clock: Clock,
    private val logger: Logger,
    private val policy: CachePolicy = CachePolicy(),
    private val defaultLanguage: String? = null,
) {
    val providers: List<MetadataProvider> = providers
    private val byId = providers.associateBy { it.id }
    val health = ProviderHealth(clock)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<AppResult<String?>>>()

    fun usable(capability: ProviderCapability): List<MetadataProvider> =
        providers.filter { it.isConfigured && it.supports(capability) }

    fun status(): List<ProviderStatus> = providers.map { health.status(it.id) }

    // ------------------------------------------------------------------ search

    /** Search with fallback across providers: the first provider with results wins. */
    suspend fun search(query: MetadataQuery): AppResult<Sourced<List<MetadataCandidate>>> {
        var lastError: AppError? = null
        var emptyFrom: ProviderId? = null
        for (provider in searchProviders(query.type)) {
            when (val r = search(query, provider.id)) {
                is AppResult.Ok -> if (r.value.value.isNotEmpty()) return r else emptyFrom = provider.id
                is AppResult.Err -> lastError = r.error
            }
        }
        emptyFrom?.let { return AppResult.Ok(Sourced(it, emptyList(), fromCache = false)) }
        return AppResult.Err(lastError ?: AppError.NotConfigured("No metadata provider is configured"))
    }

    /** Providers that can search for [type], in configured order. */
    fun searchProviders(type: MetadataType?): List<MetadataProvider> = providers.filter {
        it.isConfigured && when (type) {
            MetadataType.MOVIE -> it.supports(ProviderCapability.SEARCH_MOVIES)
            MetadataType.SERIES -> it.supports(ProviderCapability.SEARCH_SERIES)
            null -> it.supports(ProviderCapability.SEARCH_MOVIES) || it.supports(ProviderCapability.SEARCH_SERIES)
        }
    }

    /** Search one specific provider (cached). */
    suspend fun search(query: MetadataQuery, provider: ProviderId): AppResult<Sourced<List<MetadataCandidate>>> {
        val p = byId[provider] ?: return AppResult.Err(AppError.NotFound("provider $provider"))
        val q = query.copy(language = query.language ?: defaultLanguage)
        val key = "search:${q.type ?: "ANY"}:${normalizeKey(q.title)}:${q.year ?: ""}:${q.language ?: ""}"
        return cached(p, key, policy.searchTtlMillis, ListSerializer(MetadataCandidate.serializer()), emptyOnNotFound = true) { p.search(q) }
    }

    // ------------------------------------------------------------------ details

    suspend fun movie(ref: MetadataRef, language: String? = null): AppResult<Sourced<MovieMetadata>> =
        details(ref, ProviderCapability.MOVIE_DETAILS, "movie:${ref.id}:${lang(language)}", policy.detailsTtlMillis, MovieMetadata.serializer()) { p, id ->
            p.movie(id, lang(language))
        }

    suspend fun series(ref: MetadataRef, language: String? = null): AppResult<Sourced<SeriesMetadata>> =
        details(ref, ProviderCapability.SERIES_DETAILS, "series:${ref.id}:${lang(language)}", policy.detailsTtlMillis, SeriesMetadata.serializer()) { p, id ->
            p.series(id, lang(language))
        }

    suspend fun season(ref: MetadataRef, season: Int, language: String? = null): AppResult<Sourced<SeasonMetadata>> =
        details(ref, ProviderCapability.SEASON_DETAILS, "season:${ref.id}:$season:${lang(language)}", policy.episodeTtlMillis, SeasonMetadata.serializer()) { p, id ->
            p.season(id, season, lang(language))
        }

    suspend fun episode(ref: MetadataRef, season: Int, episode: Int, language: String? = null): AppResult<Sourced<EpisodeMetadata>> =
        details(ref, ProviderCapability.EPISODE_DETAILS, "episode:${ref.id}:$season:$episode:${lang(language)}", policy.episodeTtlMillis, EpisodeMetadata.serializer()) { p, id ->
            p.episode(id, season, episode, lang(language))
        }

    private fun lang(language: String?) = language ?: defaultLanguage

    /**
     * Owner provider first; on a transient failure (not "not found"), any other provider that
     * can resolve the IMDb id and has the capability.
     */
    private suspend fun <T> details(
        ref: MetadataRef,
        capability: ProviderCapability,
        key: String,
        ttl: Long,
        serializer: KSerializer<T>,
        fetch: suspend (MetadataProvider, String) -> AppResult<T>,
    ): AppResult<Sourced<T>> {
        val owner = byId[ref.provider]
        var error: AppError = AppError.NotConfigured("Provider ${ref.provider} is not available")
        if (owner != null && owner.isConfigured && owner.supports(capability)) {
            when (val r = cached(owner, key, ttl, serializer) { fetch(owner, ref.id) }) {
                is AppResult.Ok -> return r
                is AppResult.Err -> if (r.error is AppError.NotFound) return r else error = r.error
            }
        }
        val imdb = ref.imdbId ?: return AppResult.Err(error)
        for (other in providers) {
            if (other.id == ref.provider || !other.isConfigured || !other.supports(capability) || !other.supports(ProviderCapability.FIND_BY_IMDB_ID)) continue
            val translated = cached(other, "find:${ref.type}:$imdb", policy.detailsTtlMillis, MetadataRef.serializer()) { other.findByImdbId(imdb, ref.type) }
            val otherRef = (translated as? AppResult.Ok)?.value?.value ?: continue
            val otherKey = key.replaceFirst(ref.id, otherRef.id)
            val r = cached(other, otherKey, ttl, serializer) { fetch(other, otherRef.id) }
            if (r is AppResult.Ok) {
                logger.i(TAG, "${ref.provider} unavailable (${error.message}); used ${other.id} via $imdb")
                return r
            }
        }
        return AppResult.Err(error)
    }

    // ------------------------------------------------------------------ cache + health core

    private suspend fun <T> cached(
        provider: MetadataProvider,
        key: String,
        ttl: Long,
        serializer: KSerializer<T>,
        emptyOnNotFound: Boolean = false,
        fetch: suspend () -> AppResult<T>,
    ): AppResult<Sourced<T>> {
        val now = clock.nowMillis()
        val hit = cache.get(provider.id, key)
        if (hit != null && hit.isFresh(now)) return decode(provider.id, hit, serializer, fromCache = true, emptyOnNotFound)

        val inflightKey = "${provider.id}|$key"
        val mine = CompletableDeferred<AppResult<String?>>()
        val existing = inFlight.putIfAbsent(inflightKey, mine)
        val raw: AppResult<String?> = if (existing != null) {
            existing.await()
        } else {
            try {
                fetchAndStore(provider, key, ttl, serializer, fetch).also { mine.complete(it) }
            } catch (t: Throwable) {
                mine.completeExceptionally(t); throw t
            } finally {
                inFlight.remove(inflightKey, mine)
            }
        }
        return when (raw) {
            is AppResult.Ok -> decode(provider.id, CacheEntry(raw.value, now, now), serializer, fromCache = existing != null, emptyOnNotFound)
            is AppResult.Err -> {
                // Graceful degradation: an expired answer beats no answer.
                if (hit != null && raw.error !is AppError.NotFound) {
                    logger.w(TAG, "${provider.id} failed (${raw.error.message}); serving stale cache for $key")
                    decode(provider.id, hit, serializer, fromCache = true, emptyOnNotFound)
                } else raw
            }
        }
    }

    /** Ok(json) / Ok(null) for "not found" / Err for failures. Updates cache and health. */
    private suspend fun <T> fetchAndStore(
        provider: MetadataProvider,
        key: String,
        ttl: Long,
        serializer: KSerializer<T>,
        fetch: suspend () -> AppResult<T>,
    ): AppResult<String?> {
        if (!health.canCall(provider.id)) {
            val status = health.status(provider.id)
            val wait = ((status.availableAt ?: clock.nowMillis()) - clock.nowMillis()) / 1000
            return AppResult.Err(AppError.RateLimited(wait.toInt().coerceAtLeast(1)))
        }
        val now = clock.nowMillis()
        return when (val r = dev.yourstream.core.common.guarded("${provider.id} request") { fetch() }) {
            is AppResult.Ok -> {
                health.onSuccess(provider.id)
                val payload = json.encodeToString(serializer, r.value)
                cache.put(provider.id, key, CacheEntry(payload, now, now + ttl))
                AppResult.Ok(payload)
            }
            is AppResult.Err -> {
                health.onError(provider.id, r.error)
                if (r.error is AppError.NotFound) {
                    health.onSuccess(provider.id)
                    cache.put(provider.id, key, CacheEntry(null, now, now + policy.notFoundTtlMillis))
                    AppResult.Ok(null)
                } else {
                    logger.w(TAG, "${provider.id} $key failed: ${r.error.message}")
                    r
                }
            }
        }
    }

    private fun <T> decode(provider: ProviderId, entry: CacheEntry, serializer: KSerializer<T>, fromCache: Boolean, emptyOnNotFound: Boolean): AppResult<Sourced<T>> {
        val payload = entry.payload
        if (payload == null) {
            @Suppress("UNCHECKED_CAST")
            return if (emptyOnNotFound) AppResult.Ok(Sourced(provider, emptyList<Any>() as T, fromCache))
            else AppResult.Err(AppError.NotFound("Not found at $provider"))
        }
        return try {
            AppResult.Ok(Sourced(provider, json.decodeFromString(serializer, payload), fromCache))
        } catch (e: Exception) {
            AppResult.Err(AppError.Unknown("Corrupt cache entry", e))
        }
    }

    private companion object {
        const val TAG = "Metadata"
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        fun normalizeKey(title: String): String =
            NON_WORD.replace(Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT), " ").trim()
    }
}
