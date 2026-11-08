package dev.yourstream.catalog.core

import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogLanguage
import dev.yourstream.catalog.api.CatalogLanguages
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogPage
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogProviderInfo
import dev.yourstream.catalog.api.CatalogSource
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.ExternalMetadataFallback
import dev.yourstream.catalog.api.LibraryAvailability
import dev.yourstream.catalog.api.LibraryPresence
import dev.yourstream.catalog.api.LocalCatalogSource
import dev.yourstream.catalog.api.Sourced
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.network.ServiceHealth
import dev.yourstream.core.network.ServiceState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class AvailabilityState {
    /** Manifest fetched live. */
    AVAILABLE,
    /** Live check failed; working from a cached manifest (results may be stale). */
    STALE,
    /** Rate limited / circuit open: skipped for a while. */
    PAUSED,
    /** No manifest at all (never reached, or not a valid add-on). */
    UNAVAILABLE,
    /** Not checked yet. */
    UNKNOWN,
}

data class ProviderAvailability(
    val provider: CatalogProviderId,
    val displayName: String,
    val attribution: String,
    val state: AvailabilityState,
    val catalogCount: Int,
    val languages: List<String>,
    val baseUrl: String?,
    val checkedAt: Long?,
    val error: String?,
)

/** Where to continue a discover listing. */
sealed interface DiscoverCursor {
    data class Provider(val provider: CatalogProviderId, val catalogId: String, val skip: Int) : DiscoverCursor
    data class Local(val skip: Int) : DiscoverCursor
}

data class DiscoverRequest(
    /** ISO code ("ta"); null = any. */
    val language: String? = null,
    val type: CatalogType? = null,
    /** "trending", "ott", "dubbed", "new", "recommended"; null = any. */
    val tag: String? = null,
    /** Continue a previous listing (from [DiscoverResult.next]). */
    val cursor: DiscoverCursor? = null,
)

data class DiscoverResult(
    val items: List<CatalogItem>,
    val source: CatalogSource,
    /** The catalog that answered (null for local/fallback). */
    val catalog: CatalogDescriptor?,
    val next: DiscoverCursor?,
    val stale: Boolean,
    /** Why earlier sources in the chain were skipped (for diagnostics). */
    val skipped: List<String>,
)

/**
 * The only way the app talks to catalog providers. It knows no concrete provider: they are
 * injected, in order.
 *
 * Fallback chains (each step only when the previous one fails or has nothing):
 *   discover:  provider A catalog → provider B catalog → local library → empty result
 *   metadata:  owning provider → other providers (same IMDb id) → external metadata (TMDB/Cinemeta)
 *              → local metadata → the listing itself (partial)
 *   search:    providers with search → external metadata → local library
 *
 * Every provider answer is cached with a TTL; an expired answer is still served when the
 * provider fails (flagged stale). Rate limits and repeated failures pause a provider.
 */
class CatalogManager(
    private val providers: List<CatalogProvider>,
    private val cache: CatalogCache,
    private val clock: Clock,
    private val logger: Logger,
    private val external: ExternalMetadataFallback? = null,
    private val local: LocalCatalogSource? = null,
    private val library: LibraryAvailability? = null,
    private val policy: CatalogCachePolicy = CatalogCachePolicy(),
) {
    private val health = ServiceHealth(clock)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val infos = ConcurrentHashMap<CatalogProviderId, CatalogProviderInfo>()
    private val states = ConcurrentHashMap<CatalogProviderId, ProviderAvailability>()
    private val byId = providers.associateBy { it.id }

    // ------------------------------------------------------------------ availability

    /**
     * Checks each provider's manifest (cached for a day unless [force]). A provider whose live
     * check fails but which has a cached manifest stays usable as STALE.
     */
    suspend fun refreshAvailability(force: Boolean = false): List<ProviderAvailability> {
        for (p in providers) checkProvider(p, force)
        return availability()
    }

    fun availability(): List<ProviderAvailability> = providers.map { p ->
        val s = states[p.id] ?: ProviderAvailability(p.id, p.displayName, p.attribution, AvailabilityState.UNKNOWN, 0, emptyList(), null, null, null)
        val h = health.status(p.id.value)
        if (h.state == ServiceState.AVAILABLE) s else s.copy(state = AvailabilityState.PAUSED, error = h.lastError ?: s.error)
    }

    private suspend fun checkProvider(p: CatalogProvider, force: Boolean): CatalogProviderInfo? {
        val now = clock.nowMillis()
        val cached = cache.get(p.id.value, MANIFEST)
        if (!force && cached != null && cached.isFresh(now)) {
            decode(cached.payload, CatalogProviderInfo.serializer())?.let { info ->
                if (infos[p.id] == null) record(p, info, AvailabilityState.AVAILABLE, cached.fetchedAt, null)
                return info
            }
        }
        if (!health.canCall(p.id.value)) return infos[p.id] ?: decode(cached?.payload, CatalogProviderInfo.serializer())
        return when (val r = p.describe()) {
            is AppResult.Ok -> {
                health.onSuccess(p.id.value)
                cache.put(p.id.value, MANIFEST, CatalogCacheEntry(json.encodeToString(CatalogProviderInfo.serializer(), r.value), now, now + policy.manifestTtlMillis))
                record(p, r.value, AvailabilityState.AVAILABLE, now, null)
                r.value
            }
            is AppResult.Err -> {
                health.onError(p.id.value, r.error)
                val stale = decode(cached?.payload, CatalogProviderInfo.serializer())
                if (stale != null) record(p, stale, AvailabilityState.STALE, now, r.error.message)
                else states[p.id] = ProviderAvailability(p.id, p.displayName, p.attribution, AvailabilityState.UNAVAILABLE, 0, emptyList(), null, now, r.error.message)
                logger.w(TAG, "${p.id} unavailable: ${r.error.message}")
                stale
            }
        }
    }

    private fun record(p: CatalogProvider, info: CatalogProviderInfo, state: AvailabilityState, at: Long, error: String?) {
        infos[p.id] = info
        states[p.id] = ProviderAvailability(p.id, p.displayName, p.attribution, state, info.catalogs.size, info.languages, info.baseUrl, at, error)
    }

    private suspend fun info(p: CatalogProvider): CatalogProviderInfo? = infos[p.id] ?: checkProvider(p, force = false)

    // ------------------------------------------------------------------ catalogs, languages, genres

    suspend fun catalogs(type: CatalogType? = null): List<CatalogDescriptor> =
        providers.flatMap { p -> info(p)?.catalogs.orEmpty() }.filter { type == null || it.type == type }.filterNot { it.requiresExtra }

    /** Languages offered by at least one provider; Indian languages first. Local-only when no provider is up. */
    suspend fun languages(): List<Pair<CatalogLanguage, List<CatalogProviderId>>> {
        val offered = linkedMapOf<String, MutableList<CatalogProviderId>>()
        for (p in providers) info(p)?.languages?.forEach { offered.getOrPut(it) { mutableListOf() }.add(p.id) }
        val codes = (offered.keys + CatalogLanguages.INDIAN_CODES).distinct()
        return codes.map { CatalogLanguages.of(it) to offered[it].orEmpty().distinct() }
            .sortedWith(compareByDescending<Pair<CatalogLanguage, List<CatalogProviderId>>> { it.second.isNotEmpty() }
                .thenByDescending { it.first.isIndian }.thenBy { CatalogLanguages.ALL.indexOf(it.first).let { i -> if (i < 0) 999 else i } })
    }

    /** Declared genre options plus genres seen on cached pages of the catalogs. */
    suspend fun genres(type: CatalogType? = null): List<String> {
        val declared = catalogs(type).flatMap { it.genres }
        return (declared + observedGenres.filterKeys { type == null || it == type }.values.flatten()).distinct().sorted()
    }

    private val observedGenres = ConcurrentHashMap<CatalogType, MutableSet<String>>()

    // ------------------------------------------------------------------ pages / discover

    suspend fun page(catalog: CatalogDescriptor, skip: Int = 0): AppResult<Sourced<CatalogPage>> {
        val p = byId[catalog.provider] ?: return AppResult.Err(AppError.NotFound("provider ${catalog.provider}"))
        val key = "page:${catalog.type}:${catalog.id}:$skip"
        return cached(p, key, policy.pageTtlMillis, CatalogPage.serializer()) { p.page(catalog, skip) }.also { r ->
            if (r is AppResult.Ok) r.value.value.items.forEach { item ->
                observedGenres.getOrPut(item.ref.type) { ConcurrentHashMap.newKeySet() }.addAll(item.genres)
            }
        }
    }

    suspend fun discover(request: DiscoverRequest): DiscoverResult {
        val skipped = mutableListOf<String>()
        when (val cursor = request.cursor) {
            is DiscoverCursor.Local -> return localDiscover(request, cursor.skip, skipped)
            is DiscoverCursor.Provider -> {
                val catalog = catalogs().firstOrNull { it.provider == cursor.provider && it.id == cursor.catalogId }
                if (catalog != null) {
                    when (val r = page(catalog, cursor.skip)) {
                        is AppResult.Ok -> return result(catalog, r.value, skipped)
                        is AppResult.Err -> skipped += "${catalog.provider}/${catalog.id}: ${r.error.message}"
                    }
                }
                return DiscoverResult(emptyList(), CatalogSource.Fallback, null, null, false, skipped)
            }
            null -> Unit
        }

        val candidates = catalogs(request.type).filter { c ->
            (request.language == null || request.language in c.languages) && (request.tag == null || request.tag in c.tags)
        }
        if (candidates.isEmpty()) skipped += "no provider catalog for language=${request.language} type=${request.type} tag=${request.tag}"
        for (catalog in candidates) {
            when (val r = page(catalog)) {
                is AppResult.Ok -> if (r.value.value.items.isNotEmpty()) return result(catalog, r.value, skipped) else skipped += "${catalog.provider}/${catalog.id}: empty"
                is AppResult.Err -> skipped += "${catalog.provider}/${catalog.id}: ${r.error.message}"
            }
        }
        return localDiscover(request, 0, skipped)
    }

    private fun result(catalog: CatalogDescriptor, page: Sourced<CatalogPage>, skipped: List<String>) = DiscoverResult(
        items = page.value.items,
        source = CatalogSource.Provider(catalog.provider),
        catalog = catalog,
        next = page.value.nextSkip?.let { DiscoverCursor.Provider(catalog.provider, catalog.id, it) },
        stale = page.stale,
        skipped = skipped,
    )

    private suspend fun localDiscover(request: DiscoverRequest, skip: Int, skipped: MutableList<String>): DiscoverResult {
        val items = local?.discover(request.language, request.type, skip, LOCAL_PAGE).orEmpty()
        if (items.isNotEmpty()) {
            return DiscoverResult(items, CatalogSource.Local, null, if (items.size == LOCAL_PAGE) DiscoverCursor.Local(skip + items.size) else null, false, skipped)
        }
        if (local == null) skipped += "no local source"
        return DiscoverResult(emptyList(), CatalogSource.Fallback, null, null, false, skipped)
    }

    // ------------------------------------------------------------------ search

    suspend fun search(query: String, type: CatalogType? = null): Sourced<List<CatalogItem>> {
        val q = query.trim()
        if (q.isEmpty()) return Sourced(emptyList(), CatalogSource.Fallback)
        for (p in providers) {
            val i = info(p) ?: continue
            if (i.catalogs.none { it.supportsSearch && (type == null || it.type == type) }) continue
            val key = "search:${type ?: "ANY"}:${q.lowercase(Locale.ROOT)}"
            val r = cached(p, key, policy.searchTtlMillis, ListSerializer(CatalogItem.serializer())) { p.search(q, type) }
            if (r is AppResult.Ok && r.value.value.isNotEmpty()) return r.value
        }
        external?.search(q, type)?.let { r -> if (r is AppResult.Ok && r.value.value.isNotEmpty()) return r.value }
        val localHits = local?.search(q, type, LOCAL_PAGE).orEmpty()
        if (localHits.isNotEmpty()) return Sourced(localHits, CatalogSource.Local)
        return Sourced(emptyList(), CatalogSource.Fallback)
    }

    // ------------------------------------------------------------------ metadata

    suspend fun metadata(item: CatalogItem): Sourced<CatalogMetadata> {
        // A library item already carries what the library knows; no network needed.
        if (item.ref.provider == CatalogProviderId.LOCAL) local?.metadata(item)?.let { return Sourced(it, CatalogSource.Local) }
        val owner = byId[item.ref.provider]
        if (owner != null && supportsMeta(owner, item.ref.id)) {
            ownerMeta(owner, item.ref)?.let { return it }
        }
        // Another catalog provider that knows the same title by its IMDb id.
        item.ref.imdbId?.let { imdb ->
            for (p in providers) {
                if (p.id == item.ref.provider || !supportsMeta(p, imdb)) continue
                ownerMeta(p, CatalogItemRef(p.id, item.ref.type, imdb, imdb, item.ref.tmdbId))?.let { return it }
            }
        }
        external?.metadata(item)?.let { r -> if (r is AppResult.Ok) return r.value }
        local?.metadata(item)?.let { return Sourced(it, CatalogSource.Local) }
        return Sourced(item.toPartialMetadata(), CatalogSource.Fallback)
    }

    private suspend fun ownerMeta(p: CatalogProvider, ref: CatalogItemRef): Sourced<CatalogMetadata>? =
        (cached(p, "meta:${ref.type}:${ref.id}", policy.metadataTtlMillis, CatalogMetadata.serializer()) { p.metadata(ref) } as? AppResult.Ok)?.value

    private suspend fun supportsMeta(p: CatalogProvider, id: String): Boolean {
        val i = info(p) ?: return false
        return i.supportsMeta && i.metaIdPrefixes.any { id.startsWith(it) }
    }

    // ------------------------------------------------------------------ library presence

    /** Catalog items paired with the user's own copy, if any. A catalog item alone is never playable. */
    suspend fun withLibraryPresence(items: List<CatalogItem>): List<Pair<CatalogItem, LibraryPresence?>> {
        val presence = library?.presence(items).orEmpty()
        return items.map { it to presence[it.ref] }
    }

    /** Drops cache entries that expired more than [keepStaleForMillis] ago (stale copies are the offline fallback). */
    suspend fun purgeCache(keepStaleForMillis: Long = 30 * 24 * CatalogCachePolicy.HOUR) =
        cache.purge(clock.nowMillis() - keepStaleForMillis)

    // ------------------------------------------------------------------ cache + health

    private suspend fun <T> cached(p: CatalogProvider, key: String, ttl: Long, serializer: KSerializer<T>, fetch: suspend () -> AppResult<T>): AppResult<Sourced<T>> {
        val now = clock.nowMillis()
        val hit = cache.get(p.id.value, key)
        if (hit != null && hit.isFresh(now)) return fromEntry(p, hit, serializer, stale = false)
        if (!health.canCall(p.id.value)) {
            return hit?.let { fromEntry(p, it, serializer, stale = true) } ?: AppResult.Err(AppError.RateLimited(health.waitSeconds(p.id.value)))
        }
        return when (val r = fetch()) {
            is AppResult.Ok -> {
                health.onSuccess(p.id.value)
                cache.put(p.id.value, key, CatalogCacheEntry(json.encodeToString(serializer, r.value), now, now + ttl))
                AppResult.Ok(Sourced(r.value, CatalogSource.Provider(p.id)))
            }
            is AppResult.Err -> {
                if (r.error is AppError.NotFound) {
                    health.onSuccess(p.id.value)
                    cache.put(p.id.value, key, CatalogCacheEntry(null, now, now + policy.notFoundTtlMillis))
                    return r
                }
                if (r.error !is AppError.InvalidState) health.onError(p.id.value, r.error)
                hit?.let { fromEntry(p, it, serializer, stale = true) }?.takeIf { it is AppResult.Ok }
                    ?.also { logger.w(TAG, "${p.id} failed (${r.error.message}); serving stale $key") }
                    ?: r
            }
        }
    }

    private fun <T> fromEntry(p: CatalogProvider, e: CatalogCacheEntry, serializer: KSerializer<T>, stale: Boolean): AppResult<Sourced<T>> {
        val payload = e.payload ?: return AppResult.Err(AppError.NotFound("not found at ${p.id}"))
        val value = decode(payload, serializer) ?: return AppResult.Err(AppError.Unknown("corrupt cache entry"))
        return AppResult.Ok(Sourced(value, CatalogSource.Provider(p.id), fromCache = true, stale = stale))
    }

    private fun <T> decode(payload: String?, serializer: KSerializer<T>): T? =
        payload?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private companion object {
        const val TAG = "Catalog"
        const val MANIFEST = "manifest"
        const val LOCAL_PAGE = 30
    }
}

fun CatalogItem.toPartialMetadata() = CatalogMetadata(
    ref = ref, title = title, year = year, description = description, posterUrl = posterUrl, backdropUrl = backdropUrl,
    genres = genres, cast = cast, directors = directors, runtimeMinutes = runtimeMinutes, rating = rating, partial = true,
)
