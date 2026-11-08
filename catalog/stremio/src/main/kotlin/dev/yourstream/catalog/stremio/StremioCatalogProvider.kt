package dev.yourstream.catalog.stremio

import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogExtra
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogPage
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.api.CatalogProviderInfo
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.network.RateLimiter
import dev.yourstream.core.network.getJson
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * The Stremio add-on protocol, for its CATALOG and META resources only:
 *   GET {base}/manifest.json
 *   GET {base}/catalog/{type}/{id}[/{extra=value&…}].json
 *   GET {base}/meta/{type}/{id}.json
 * There is intentionally no method for the stream or subtitles resources, even when an add-on
 * offers them: this app never takes media from catalogs.
 */
class StremioCatalogProvider(
    private val client: HttpClient,
    val definition: StremioAddonDefinition,
    clock: Clock = Clock.System,
    requestsPerSecond: Int = 4,
) : CatalogProvider {

    override val id = definition.id
    override val displayName = definition.displayName
    override val attribution = definition.attribution

    private val limiter = RateLimiter(requestsPerSecond, 1_000, clock)

    @Volatile private var activeBase: String = definition.baseUrls.first()
    @Volatile private var info: CatalogProviderInfo? = null

    // ------------------------------------------------------------------ manifest

    override suspend fun describe(): AppResult<CatalogProviderInfo> {
        var last: AppError = AppError.Network()
        for (base in listOf(activeBase) + definition.baseUrls.filter { it != activeBase }) {
            limiter.acquire()
            when (val r = client.getJson("$base/manifest.json", JsonElement.serializer())) {
                is AppResult.Ok -> {
                    val parsed = (r.value as? JsonObject)?.let { parseManifest(it, base) }
                        ?: return AppResult.Err(AppError.Unknown("Manifest at $base is not an object"))
                    activeBase = base
                    info = parsed
                    return AppResult.Ok(parsed)
                }
                is AppResult.Err -> last = r.error
            }
        }
        return AppResult.Err(last)
    }

    private fun parseManifest(m: JsonObject, base: String): CatalogProviderInfo {
        val topPrefixes = m.strings("idPrefixes")
        val resources = mutableListOf<String>()
        val metaPrefixes = mutableListOf<String>()
        for (r in m.array("resources")) {
            when (r) {
                is JsonPrimitive -> { resources += r.content; if (r.content == "meta") metaPrefixes += topPrefixes }
                is JsonObject -> r.str("name")?.let { name ->
                    resources += name
                    if (name == "meta") metaPrefixes += r.strings("idPrefixes").ifEmpty { topPrefixes }
                }
                else -> Unit
            }
        }
        val catalogs = m.array("catalogs").mapNotNull { it as? JsonObject }.mapNotNull { c ->
            val type = typeOf(c.str("type")) ?: return@mapNotNull null
            val entry = StremioCatalogEntry(type, c.str("id") ?: return@mapNotNull null, c.str("name") ?: c.str("id")!!)
            if (!definition.includeCatalog(entry)) return@mapNotNull null
            val extras = c.array("extra").mapNotNull { it as? JsonObject }.mapNotNull { e ->
                e.str("name")?.let { CatalogExtra(it, e.bool("isRequired"), e.strings("options")) }
            }.toMutableList()
            // Legacy manifest fields.
            c.strings("extraSupported").forEach { n -> if (extras.none { it.name == n }) extras += CatalogExtra(n, n in c.strings("extraRequired")) }
            c.strings("genres").takeIf { it.isNotEmpty() }?.let { g ->
                val i = extras.indexOfFirst { it.name == "genre" }
                if (i >= 0) extras[i] = extras[i].copy(options = extras[i].options.ifEmpty { g }) else extras += CatalogExtra("genre", options = g)
            }
            CatalogDescriptor(id, entry.id, type, entry.name, definition.catalogLanguages(entry), definition.catalogTags(entry), extras)
        }
        return CatalogProviderInfo(id, m.str("name") ?: displayName, m.str("version"), m.str("description"), catalogs,
            resources.distinct(), metaPrefixes.distinct(), base)
    }

    private suspend fun knownInfo(): AppResult<CatalogProviderInfo> = info?.let { AppResult.Ok(it) } ?: describe()

    // ------------------------------------------------------------------ catalog

    override suspend fun page(catalog: CatalogDescriptor, skip: Int, genre: String?): AppResult<CatalogPage> {
        // Catalogs without paging have one page only: do not ask the add-on for "skip" it ignores.
        if (skip > 0 && !catalog.supportsPaging) return AppResult.Ok(CatalogPage(emptyList(), null))
        val extras = buildList {
            genre?.let { add("genre" to it) }
            if (skip > 0) add("skip" to skip.toString())
        }
        return fetchCatalog(catalog, extras).let { r ->
            when (r) {
                // Paging counts what the add-on returned, including entries we dropped.
                is AppResult.Ok -> AppResult.Ok(CatalogPage(r.value.items, if (catalog.supportsPaging && r.value.rawCount > 0) skip + r.value.rawCount else null))
                is AppResult.Err -> r
            }
        }
    }

    override suspend fun search(query: String, type: CatalogType?): AppResult<List<CatalogItem>> {
        val info = when (val r = knownInfo()) { is AppResult.Ok -> r.value; is AppResult.Err -> return r }
        val searchable = info.catalogs.filter { it.supportsSearch && (type == null || it.type == type) }
        if (searchable.isEmpty()) return AppResult.Err(AppError.InvalidState("$displayName has no search"))
        val all = mutableListOf<CatalogItem>()
        for (catalog in searchable) {
            when (val r = fetchCatalog(catalog, listOf("search" to query))) {
                is AppResult.Ok -> all += r.value.items
                is AppResult.Err -> if (all.isEmpty() && catalog == searchable.last()) return r
            }
        }
        return AppResult.Ok(all.distinctBy { it.ref.id })
    }

    private class Fetched(val items: List<CatalogItem>, val rawCount: Int)

    private suspend fun fetchCatalog(catalog: CatalogDescriptor, extras: List<Pair<String, String>>): AppResult<Fetched> {
        val extraPath = if (extras.isEmpty()) "" else "/" + extras.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val path = "/catalog/${typePath(catalog.type)}/${enc(catalog.id)}$extraPath.json"
        return when (val r = getWithFailover(path)) {
            is AppResult.Err -> r
            is AppResult.Ok -> {
                val metas = (r.value as? JsonObject)?.array("metas").orEmpty()
                val items = metas.mapNotNull { (it as? JsonObject)?.let { m -> toItem(m, catalog) } }.filter(definition.includeItem)
                AppResult.Ok(Fetched(items, metas.size))
            }
        }
    }

    // ------------------------------------------------------------------ meta

    override suspend fun metadata(ref: CatalogItemRef): AppResult<CatalogMetadata> {
        val info = when (val r = knownInfo()) { is AppResult.Ok -> r.value; is AppResult.Err -> return r }
        if (!info.supportsMeta || info.metaIdPrefixes.none { ref.id.startsWith(it) }) {
            return AppResult.Err(AppError.InvalidState("$displayName has no metadata for ${ref.id.take(20)}"))
        }
        return when (val r = getWithFailover("/meta/${typePath(ref.type)}/${enc(ref.id)}.json")) {
            is AppResult.Err -> r
            // Add-ons answer unknown ids with {}, {"meta":null} or even [].
            is AppResult.Ok -> (r.value as? JsonObject)?.obj("meta")?.let { AppResult.Ok(toMetadata(it, ref)) }
                ?: AppResult.Err(AppError.NotFound("${ref.id.take(40)} at $displayName"))
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Primary base first; on network/server trouble the other mirrors, remembering the one that worked. */
    private suspend fun getWithFailover(path: String): AppResult<JsonElement> {
        val bases = listOf(activeBase) + definition.baseUrls.filter { it != activeBase }
        var last: AppResult<JsonElement> = AppResult.Err(AppError.Network())
        for (base in bases) {
            limiter.acquire()
            val r = client.getJson(base + path, JsonElement.serializer())
            if (r is AppResult.Ok) { activeBase = base; return r }
            last = r
            val e = (r as AppResult.Err).error
            val transient = e is AppError.Network || e is AppError.Timeout || (e is AppError.Remote && e.isServerError)
            if (!transient) return r
        }
        return last
    }

    private fun toItem(m: JsonObject, catalog: CatalogDescriptor): CatalogItem? {
        val rawId = m.str("id") ?: return null
        val ids = StremioIds.parse(rawId)
        val type = typeOf(m.str("type")) ?: catalog.type
        val title = m.str("name") ?: ids.title ?: return null
        return CatalogItem(
            ref = CatalogItemRef(id, type, rawId, ids.imdbId, ids.tmdbId),
            title = title,
            year = firstYear(m.str("year")) ?: firstYear(m.str("releaseInfo")) ?: ids.year,
            posterUrl = m.str("poster") ?: ids.posterUrl,
            backdropUrl = m.str("background"),
            description = m.str("description") ?: ids.description,
            genres = (m.strings("genres") + m.strings("genre")).distinct(),
            rating = m.str("imdbRating")?.toDoubleOrNull(),
            runtimeMinutes = m.str("runtime")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
            cast = m.strings("cast"),
            directors = m.strings("director"),
            languages = catalog.languages,
            catalogId = catalog.id,
        )
    }

    private fun toMetadata(m: JsonObject, ref: CatalogItemRef): CatalogMetadata {
        val ids = StremioIds.parse(ref.id)
        return CatalogMetadata(
            ref = ref,
            title = m.str("name") ?: ids.title ?: ref.id,
            year = firstYear(m.str("year")) ?: firstYear(m.str("releaseInfo")) ?: ids.year,
            description = m.str("description") ?: ids.description,
            posterUrl = m.str("poster") ?: ids.posterUrl,
            backdropUrl = m.str("background"),
            genres = (m.strings("genres") + m.strings("genre")).distinct(),
            cast = m.strings("cast"),
            directors = m.strings("director"),
            runtimeMinutes = m.str("runtime")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
            rating = m.str("imdbRating")?.toDoubleOrNull(),
            releaseDate = m.str("released")?.take(10),
            originalLanguage = m.str("language"),
        )
    }

    private fun typeOf(text: String?): CatalogType? = when (text?.lowercase()) {
        "movie" -> CatalogType.MOVIE
        "series", "tv" -> CatalogType.SERIES
        else -> null
    }

    private fun typePath(type: CatalogType) = if (type == CatalogType.MOVIE) "movie" else "series"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
