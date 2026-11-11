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

/**
 * One way to watch a title, as a stream add-on (Stremio protocol) offers it. Exactly one of
 * [url] (playable over HTTP: a file, HLS or DASH), [externalUrl] (opens elsewhere) or
 * [infoHash] (a torrent, streamed by the built-in torrent engine) is normally set.
 */
data class AddonStream(
    val addon: String,
    /** Short label the add-on gives ("1080p", "Torrentio\n4K"). */
    val name: String,
    /** Longer description: file name, size, source. */
    val description: String?,
    val url: String?,
    val externalUrl: String?,
    val infoHash: String?,
    /** HTTP headers the stream needs (behaviorHints.proxyHeaders.request). */
    val headers: Map<String, String> = emptyMap(),
    /** Torrents: which file of the torrent is the video (null: the largest video file). */
    val fileIdx: Int? = null,
    /** Torrents: extra peer sources the add-on knows ("tracker:udp://…", "dht:…"). */
    val sources: List<String> = emptyList(),
    /** behaviorHints.filename: the video's file name, when the add-on gives it. */
    val fileName: String? = null,
    /** behaviorHints.videoSize in bytes, when known. */
    val sizeBytes: Long? = null,
) {
    val playable: Boolean get() = url != null && (url.startsWith("https://") || url.startsWith("http://"))
    val isTorrent: Boolean get() = !playable && infoHash != null && TORRENT_HASH.matches(infoHash)

    companion object {
        private val TORRENT_HASH = Regex("(?i)[0-9a-f]{40}")
    }
}

/** An add-on that answers "how can I watch X?" ([type] movie/series, [id] e.g. "tt0111161" or "tt0903747:1:2"). */
interface StreamProvider {
    val id: CatalogProviderId
    val displayName: String
    /** Whether the add-on offers streams at all (from its manifest). */
    suspend fun hasStreams(): Boolean

    /**
     * Whether the add-on answers for this kind of id (its manifest's stream types and id
     * prefixes; an add-on that names none is asked for everything).
     */
    suspend fun handles(type: CatalogType, id: String): Boolean = true

    suspend fun streams(type: CatalogType, id: String): AppResult<List<AddonStream>>
}
