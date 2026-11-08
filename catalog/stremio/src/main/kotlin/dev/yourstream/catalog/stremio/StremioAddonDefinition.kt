package dev.yourstream.catalog.stremio

import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogType

/** A raw catalog entry from a manifest, as given to the definition's mapping functions. */
data class StremioCatalogEntry(val type: CatalogType, val id: String, val name: String)

/**
 * Everything specific about one Stremio add-on. Concrete providers are just definitions; the
 * protocol logic is shared ([StremioCatalogProvider]).
 */
data class StremioAddonDefinition(
    val id: CatalogProviderId,
    val displayName: String,
    /** Shown with its results. */
    val attribution: String,
    /** Manifest URLs, primary first; the rest are mirrors used when the primary fails. */
    val manifestUrls: List<String>,
    /** ISO language codes a catalog is about (from its id/name); empty = mixed. */
    val catalogLanguages: (StremioCatalogEntry) -> List<String> = { emptyList() },
    val catalogTags: (StremioCatalogEntry) -> List<String> = { emptyList() },
    /** Catalogs to hide (e.g. ones that need configuration we do not have). */
    val includeCatalog: (StremioCatalogEntry) -> Boolean = { true },
    /** Entries that are not titles (announcements, promos) are dropped. */
    val includeItem: (CatalogItem) -> Boolean = { true },
) {
    init {
        require(manifestUrls.isNotEmpty() && manifestUrls.all { it.startsWith("https://") && it.endsWith("/manifest.json") }) {
            "manifest URLs must be https://…/manifest.json"
        }
    }

    val baseUrls: List<String> get() = manifestUrls.map { it.removeSuffix("/manifest.json") }

    companion object {
        /** Any user-supplied add-on (catalog/meta only), e.g. from a settings screen. */
        fun custom(manifestUrl: String, name: String = "Custom catalog"): StremioAddonDefinition {
            val normalized = manifestUrl.trim().replaceFirst("stremio://", "https://")
            return StremioAddonDefinition(
                id = CatalogProviderId("custom:" + normalized.removePrefix("https://").removeSuffix("/manifest.json")),
                displayName = name,
                attribution = "Catalog by $name",
                manifestUrls = listOf(normalized),
            )
        }
    }
}
