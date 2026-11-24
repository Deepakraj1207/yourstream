package dev.yourstream.desktop.addons

import dev.yourstream.catalog.api.AddonLinks
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogPage
import dev.yourstream.catalog.api.CatalogProviderInfo
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.catalog.core.InMemoryCatalogCache
import dev.yourstream.catalog.stremio.StremioAddonDefinition
import dev.yourstream.catalog.stremio.StremioCatalogProvider
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.desktop.data.SettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An installed add-on: its link and, once it answered, what its manifest says. */
data class Addon(val url: String, val info: CatalogProviderInfo? = null, val error: String? = null) {
    val name: String get() = info?.name ?: url.removePrefix("https://").substringBefore('/')
}

/** One add-on's answer for a title's streams (they arrive one add-on at a time). */
data class StreamGroup(val addon: String, val streams: List<AddonStream>, val error: String? = null)

/**
 * Stremio / Nuvio add-ons on the desktop, with the Android app's shared code: the add-on protocol
 * (catalog:stremio) and catalog handling (catalog:core). The installed links are kept in
 * settings.json in their order (which orders Discover and the stream groups); Cinemeta is
 * installed at first, as on Android.
 *
 * Every add-on request runs on Dispatchers.IO, never on the caller's dispatcher: the screens call
 * these from Compose (the UI thread), and reading and parsing add-on answers is not UI work.
 */
class AddonService(private val http: HttpClient, private val settings: SettingsStore, private val logger: Logger) {
    private var providers: List<StremioCatalogProvider> = emptyList()
    private var manager: CatalogManager = CatalogManager(emptyList(), InMemoryCatalogCache(), Clock.System, logger)
    private val _addons = MutableStateFlow<List<Addon>>(emptyList())
    val addons: StateFlow<List<Addon>> = _addons.asStateFlow()

    init { rebuild(settings.settings.value.addons) }

    @Synchronized
    private fun rebuild(urls: List<String>) {
        providers = urls.mapNotNull { url ->
            runCatching { StremioCatalogProvider(http, StremioAddonDefinition.custom(url, url.removePrefix("https://").substringBefore('/'))) }.getOrNull()
        }
        manager = CatalogManager(providers, InMemoryCatalogCache(), Clock.System, logger)
        _addons.value = urls.map { u -> _addons.value.firstOrNull { it.url == u } ?: Addon(u) }
    }

    /** Asks every add-on for its manifest (names, catalogs, what it offers). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val current = providers
        val urls = settings.settings.value.addons
        val results = current.mapIndexed { i, p -> i to p }.map { (i, p) ->
            async {
                when (val r = p.describe()) {
                    is AppResult.Ok -> Addon(urls[i], r.value)
                    is AppResult.Err -> Addon(urls[i], null, r.error.message)
                }
            }
        }.map { it.await() }
        _addons.value = results
    }

    /** Installs the add-on at [link] (a …/manifest.json link, https or stremio://). */
    suspend fun install(link: String): AppResult<Addon> = withContext(Dispatchers.IO) { installHere(link) }

    private suspend fun installHere(link: String): AppResult<Addon> {
        val url = normalize(link) ?: return AppResult.Err(dev.yourstream.core.common.AppError.InvalidState(
            if (link.contains("nuvio", ignoreCase = true) && !link.endsWith("manifest.json")) StremioCatalogProvider.NUVIO_PLUGINS
            else "Paste the add-on's link: it starts with https:// (or stremio://) and ends in manifest.json."))
        if (url in settings.settings.value.addons) return AppResult.Err(dev.yourstream.core.common.AppError.InvalidState("This add-on is already installed."))
        val probe = StremioCatalogProvider(http, StremioAddonDefinition.custom(url))
        return when (val r = probe.describe()) {
            is AppResult.Err -> AppResult.Err(r.error)
            is AppResult.Ok -> {
                settings.update { it.copy(addons = it.addons + url) }
                rebuild(settings.settings.value.addons)
                refresh()
                AppResult.Ok(Addon(url, r.value))
            }
        }
    }

    fun remove(url: String) {
        settings.update { it.copy(addons = it.addons - url) }
        rebuild(settings.settings.value.addons)
        _addons.value = _addons.value.filter { it.url != url }
    }

    fun move(url: String, by: Int) {
        val list = settings.settings.value.addons.toMutableList()
        val i = list.indexOf(url)
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        val infos = _addons.value.associateBy { it.url }
        settings.update { it.copy(addons = list) }
        rebuild(list)
        _addons.value = list.map { infos[it] ?: Addon(it) }
    }

    suspend fun catalogs(type: CatalogType? = null): List<CatalogDescriptor> = withContext(Dispatchers.IO) { manager.catalogs(type) }.filter { !it.requiresExtra }

    suspend fun page(catalog: CatalogDescriptor, skip: Int = 0, genre: String? = null): AppResult<CatalogPage> =
        withContext(Dispatchers.IO) {
            when (val r = manager.page(catalog, skip, genre)) {
                is AppResult.Ok -> AppResult.Ok(r.value.value)
                is AppResult.Err -> r
            }
        }

    suspend fun search(query: String, type: CatalogType? = null): List<CatalogItem> = withContext(Dispatchers.IO) { manager.search(query, type).value }

    suspend fun metadata(item: CatalogItem): CatalogMetadata = withContext(Dispatchers.IO) { manager.metadata(item).value }

    /** Streams for a movie ("tt0111161") or an episode ("tt0903747:1:2"), one add-on at a time. */
    fun streams(type: CatalogType, id: String): Flow<StreamGroup> = channelFlow {
        val names = _addons.value.associate { it.url to it.name }
        val urls = settings.settings.value.addons
        providers.forEachIndexed { i, p ->
            launch {
                if (!p.hasStreams() || !p.handles(type, id)) return@launch
                val name = names[urls.getOrNull(i)] ?: p.displayName
                when (val r = p.streams(type, id)) {
                    is AppResult.Ok -> send(StreamGroup(name, r.value))
                    is AppResult.Err -> send(StreamGroup(name, emptyList(), r.error.message))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

        /** https://…/manifest.json (stremio:// becomes https://; http only on the local network), or null. */
        fun normalize(link: String): String? {
            val l = link.trim().replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            if (!l.endsWith("/manifest.json")) return null
            return l.takeIf { it.startsWith("https://") || AddonLinks.isLocalHttp(it) }
        }
    }
}
