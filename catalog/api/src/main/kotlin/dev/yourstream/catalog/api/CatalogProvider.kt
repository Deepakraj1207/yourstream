package dev.yourstream.catalog.api

import dev.yourstream.core.common.AppResult

/**
 * A source of title lists (catalogs) and title information. Optional and replaceable: the app
 * works without any. Errors are values: NotFound, RateLimited, Network/Timeout/Remote, and
 * InvalidState("not supported") when the provider lacks an operation.
 *
 * Implementations only translate their interface; caching, availability tracking and fallback
 * are done by the catalog manager.
 */
interface CatalogProvider {
    val id: CatalogProviderId
    val displayName: String
    /** Shown next to its results (attribution). */
    val attribution: String

    /** Fetches the provider's self-description (manifest). Success = available. */
    suspend fun describe(): AppResult<CatalogProviderInfo>

    /** One page of a catalog. [skip] is ignored by catalogs without paging. */
    suspend fun page(catalog: CatalogDescriptor, skip: Int = 0, genre: String? = null): AppResult<CatalogPage>

    /** Text search, for catalogs that support it. */
    suspend fun search(query: String, type: CatalogType?): AppResult<List<CatalogItem>>

    /** Details for one of this provider's items. */
    suspend fun metadata(ref: CatalogItemRef): AppResult<CatalogMetadata>
}

/** Fallback for metadata/search when catalog providers cannot answer (e.g. TMDB/Cinemeta). */
interface ExternalMetadataFallback {
    suspend fun metadata(item: CatalogItem): AppResult<Sourced<CatalogMetadata>>
    suspend fun search(query: String, type: CatalogType?): AppResult<Sourced<List<CatalogItem>>>
}

/** The user's own library as the last catalog source (works offline, without any provider). */
interface LocalCatalogSource {
    suspend fun discover(language: String?, type: CatalogType?, skip: Int, limit: Int): List<CatalogItem>
    suspend fun search(query: String, type: CatalogType?, limit: Int): List<CatalogItem>
    suspend fun metadata(item: CatalogItem): CatalogMetadata?
}

/** Whether a catalog title is in the user's library (and therefore watchable from Telegram). */
data class LibraryPresence(
    val itemId: Long,
    val title: String,
    val availableFiles: Int,
    /** How the catalog title was matched to the library item (IDs are exact, a title match is a guess). */
    val matchedBy: PresenceMatch = PresenceMatch.IMDB_ID,
)

enum class PresenceMatch { IMDB_ID, TMDB_ID, TITLE_YEAR }

interface LibraryAvailability {
    suspend fun presence(items: List<CatalogItem>): Map<CatalogItemRef, LibraryPresence>
}
