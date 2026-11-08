package dev.yourstream.catalog.api

import kotlinx.serialization.Serializable

/*
 * Catalog metadata ≠ media source ≠ playback.
 *
 * A catalog says "these titles exist / are trending in Tamil". Nothing here can be played:
 * there is deliberately no URL/stream/file field on any model. Whether the user can watch a
 * title is answered only by their own library (Telegram files), via LibraryAvailability.
 */

@Serializable
@JvmInline
value class CatalogProviderId(val value: String) {
    override fun toString() = value

    companion object {
        /** Items that come from the user's own library (LocalCatalogSource). */
        val LOCAL = CatalogProviderId("local")
    }
}

@Serializable
enum class CatalogType { MOVIE, SERIES }

/** A provider's item id plus the cross-ids we could extract from it. */
@Serializable
data class CatalogItemRef(
    val provider: CatalogProviderId,
    val type: CatalogType,
    /** Exactly as the provider issued it (opaque; needed to ask the provider again). */
    val id: String,
    val imdbId: String? = null,
    val tmdbId: String? = null,
)

/** One entry of a catalog page. */
@Serializable
data class CatalogItem(
    val ref: CatalogItemRef,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtimeMinutes: Int? = null,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    /** Languages the catalog it came from is about (ISO codes), when known. */
    val languages: List<String> = emptyList(),
    /** Which catalog listed it ("ta", "trendingmovies"). */
    val catalogId: String? = null,
)

/** Extra request parameters a catalog accepts (Stremio "extra"). */
@Serializable
data class CatalogExtra(val name: String, val isRequired: Boolean = false, val options: List<String> = emptyList())

@Serializable
data class CatalogDescriptor(
    val provider: CatalogProviderId,
    val id: String,
    val type: CatalogType,
    val name: String,
    /** ISO language codes this catalog is about; empty = mixed/unknown. */
    val languages: List<String> = emptyList(),
    /** E.g. ["dubbed"], ["ott"], ["trending"]. */
    val tags: List<String> = emptyList(),
    val extras: List<CatalogExtra> = emptyList(),
) {
    val supportsPaging: Boolean get() = extras.any { it.name == "skip" }
    val supportsSearch: Boolean get() = extras.any { it.name == "search" }
    val genres: List<String> get() = extras.firstOrNull { it.name == "genre" }?.options.orEmpty()
    val requiresExtra: Boolean get() = extras.any { it.isRequired }
}

@Serializable
data class CatalogPage(
    val items: List<CatalogItem>,
    /** Pass as skip for the next page; null = no more (or paging unsupported). */
    val nextSkip: Int?,
)

/** Fuller information about one title. [partial] = only what a catalog listing had. */
@Serializable
data class CatalogMetadata(
    val ref: CatalogItemRef,
    val title: String,
    val year: Int? = null,
    val description: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val releaseDate: String? = null,
    val originalLanguage: String? = null,
    val partial: Boolean = false,
)

/** What a provider's manifest says it can do. Used for availability detection. */
@Serializable
data class CatalogProviderInfo(
    val provider: CatalogProviderId,
    val name: String,
    val version: String?,
    val description: String?,
    val catalogs: List<CatalogDescriptor>,
    /** "catalog", "meta" (never used: "stream", "subtitles"). */
    val resources: List<String>,
    /** Id prefixes the meta resource answers for ("tt", "tmdb:"). */
    val metaIdPrefixes: List<String>,
    /** Which mirror answered. */
    val baseUrl: String,
) {
    val supportsMeta: Boolean get() = "meta" in resources
    val languages: List<String> get() = catalogs.flatMap { it.languages }.distinct()
    val genres: List<String> get() = catalogs.flatMap { it.genres }.distinct()
}

/** Where an answer came from in the fallback chain. */
@Serializable
sealed interface CatalogSource {
    @Serializable data class Provider(val id: CatalogProviderId) : CatalogSource
    /** A metadata provider (TMDB, Cinemeta) consulted as fallback. */
    @Serializable data class ExternalMetadata(val provider: String) : CatalogSource
    /** The user's own library / locally stored metadata. */
    @Serializable data object Local : CatalogSource
    /** Nothing better: built from what we already had (e.g. the listing). */
    @Serializable data object Fallback : CatalogSource
}

data class Sourced<T>(val value: T, val source: CatalogSource, val fromCache: Boolean = false, val stale: Boolean = false)
