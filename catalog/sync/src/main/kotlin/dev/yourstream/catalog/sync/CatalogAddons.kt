package dev.yourstream.catalog.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.catalog.api.CatalogProvider
import dev.yourstream.catalog.api.ExternalMetadataFallback
import dev.yourstream.catalog.api.LibraryAvailability
import dev.yourstream.catalog.api.LocalCatalogSource
import dev.yourstream.catalog.core.CatalogCache
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds catalog providers. Supplied by the app module, the only place that knows the concrete
 * built-in add-ons and how HTTP clients are made.
 */
interface CatalogProviderFactory {
    /** Add-ons that ship with the app (on by default; the user can switch them off). */
    fun builtIns(): List<CatalogProvider>

    /** A provider for an add-on the user added by its manifest URL (`https://…/manifest.json`). */
    fun custom(manifestUrl: String, name: String): CatalogProvider
}

/** One catalog add-on as the Add-ons screen shows it. */
data class CatalogAddon(
    val id: String,
    val name: String,
    /** Null for built-in add-ons. */
    val manifestUrl: String?,
    val builtIn: Boolean,
    val enabled: Boolean,
)

/**
 * The user's catalog add-ons: built-in ones (switchable) plus Stremio catalog add-ons added by
 * manifest URL. Stored on the device; the [CatalogManager] is rebuilt when the set changes.
 * Only catalog and meta resources are ever used, never streams.
 */
@Singleton
class CatalogAddons @Inject constructor(
    @ApplicationContext context: Context,
    private val factory: CatalogProviderFactory,
    private val cache: CatalogCache,
    private val clock: Clock,
    private val logger: Logger,
    private val external: ExternalMetadataFallback,
    private val local: LocalCatalogSource,
    private val library: LibraryAvailability,
) {
    private val prefs = context.getSharedPreferences("catalog_addons", Context.MODE_PRIVATE)
    private val builtIns: List<CatalogProvider> by lazy { factory.builtIns() }
    private val customProviders = HashMap<String, CatalogProvider>()

    private val _addons = MutableStateFlow(load())
    val addons: StateFlow<List<CatalogAddon>> = _addons.asStateFlow()

    private var built: Pair<List<CatalogAddon>, CatalogManager>? = null

    /** The manager for the enabled add-ons (same instance until the add-ons change). */
    @Synchronized
    fun manager(): CatalogManager {
        val current = _addons.value
        built?.takeIf { it.first == current }?.let { return it.second }
        val providers = current.filter { it.enabled }.mapNotNull { a -> provider(a) }
        return CatalogManager(providers, cache, clock, logger, external, local, library).also { built = current to it }
    }

    /**
     * Adds a Stremio catalog add-on from what the user pasted (`https://…`, `stremio://…`, with or
     * without `/manifest.json`). The manifest is fetched first: it must answer and list catalogs.
     */
    suspend fun add(input: String): AppResult<CatalogAddon> {
        val url = normalize(input) ?: return AppResult.Err(AppError.InvalidInput("ADDON_URL", "Paste the add-on's https:// or stremio:// link."))
        _addons.value.firstOrNull { it.manifestUrl == url }?.let { return AppResult.Err(AppError.InvalidInput("ADDON_DUPLICATE", "${it.name} is already added.")) }
        val probe = runCatching { factory.custom(url, "Add-on") }.getOrElse {
            return AppResult.Err(AppError.InvalidInput("ADDON_URL", "That is not an add-on link."))
        }
        val info = when (val r = probe.describe()) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return AppResult.Err(r.error)
        }
        if (info.catalogs.isEmpty()) {
            return AppResult.Err(AppError.InvalidInput("ADDON_NO_CATALOGS", "${info.name} has no catalogs. Only catalog add-ons are supported."))
        }
        val addon = CatalogAddon(probe.id.value, info.name.ifBlank { url }, url, builtIn = false, enabled = true)
        synchronized(this) {
            customProviders.remove(url)
            update(_addons.value.filterNot { it.id == addon.id } + addon)
        }
        return AppResult.Ok(addon)
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) = update(_addons.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** Removes an add-on the user added (built-in ones can only be switched off). */
    @Synchronized
    fun remove(id: String) = update(_addons.value.filterNot { it.id == id && !it.builtIn })

    private fun provider(a: CatalogAddon): CatalogProvider? =
        if (a.builtIn) builtIns.firstOrNull { it.id.value == a.id }
        else a.manifestUrl?.let { url -> customProviders.getOrPut(url) { factory.custom(url, a.name) } }

    private fun update(list: List<CatalogAddon>) {
        val disabled = list.filter { it.builtIn && !it.enabled }.map { it.id }.toSet()
        val custom = list.filter { !it.builtIn }.joinToString("\n") { listOf(it.manifestUrl, it.name, if (it.enabled) "1" else "0").joinToString(SEP) }
        prefs.edit().putStringSet(KEY_DISABLED, disabled).putString(KEY_CUSTOM, custom).apply()
        _addons.value = list
    }

    private fun load(): List<CatalogAddon> {
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty()
        val builtIn = builtIns.map { CatalogAddon(it.id.value, it.displayName, null, builtIn = true, enabled = it.id.value !in disabled) }
        val custom = prefs.getString(KEY_CUSTOM, "").orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split(SEP)
            val url = parts.getOrNull(0)?.let(::normalize) ?: return@mapNotNull null
            val p = runCatching { factory.custom(url, parts.getOrNull(1) ?: url) }.getOrNull() ?: return@mapNotNull null
            customProviders[url] = p
            CatalogAddon(p.id.value, parts.getOrNull(1) ?: url, url, builtIn = false, enabled = parts.getOrNull(2) != "0")
        }
        return builtIn + custom
    }

    companion object {
        private const val KEY_DISABLED = "disabled_builtin"
        private const val KEY_CUSTOM = "custom"
        private const val SEP = "\t"

        /** "stremio://host/x" or "https://host/x/" → "https://host/x/manifest.json"; null if not a web link (pure; tested). */
        fun normalize(input: String): String? {
            var s = input.trim().replace(Regex("\\s+"), "")
            if (s.isEmpty()) return null
            s = when {
                s.startsWith("stremio://", ignoreCase = true) -> "https://" + s.substring("stremio://".length)
                s.startsWith("https://", ignoreCase = true) -> s
                s.startsWith("http://", ignoreCase = true) -> return null
                s.contains("://") -> return null
                else -> "https://$s"
            }
            s = s.substringBefore('#').trimEnd('/')
            if (!s.endsWith("/manifest.json")) s += "/manifest.json"
            val host = s.removePrefix("https://").substringBefore('/')
            if (host.isEmpty() || !host.contains('.')) return null
            return s
        }
    }
}
