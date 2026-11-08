package dev.yourstream.search.core

enum class ItemKind { MOVIE, SERIES, VIDEO, AUDIO }

enum class WatchState { ANY, UNWATCHED, IN_PROGRESS, WATCHED }

enum class SearchSort {
    /** Best match first (default with text; without text: most filters satisfied, then newest). */
    RELEVANCE, TITLE, YEAR, RECENTLY_ADDED, RECENTLY_WATCHED, RATING, SIZE,
}

/** Hard filters (from UI controls or `key:value` query operators): results must satisfy all. */
data class SearchFilters(
    val kinds: Set<ItemKind> = emptySet(),
    /** Any of these audio languages (normalized codes). */
    val languages: Set<String> = emptySet(),
    /** Any of these genres (normalized names). */
    val genres: Set<String> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** Has a season with this number. */
    val season: Int? = null,
    val minResolution: Int? = null,
    /** Any of these release qualities (normalized: "web dl", "bluray" …). */
    val qualities: Set<String> = emptySet(),
    /** Files from any of these Telegram chats. */
    val chatIds: Set<Long> = emptySet(),
    /** Metadata provider ("tmdb", "cinemeta"); "none" = not matched. */
    val providers: Set<String> = emptySet(),
    val watch: WatchState = WatchState.ANY,
    val includeHidden: Boolean = false,
    val includeUnavailable: Boolean = false,
)

data class SearchRequest(
    val text: String = "",
    val filters: SearchFilters = SearchFilters(),
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** Reverse the natural direction of [sort]. */
    val reversed: Boolean = false,
    val offset: Int = 0,
    val limit: Int = 30,
)

/** A query word interpreted as a filter. Soft: satisfying it ranks higher, but a title containing the word also matches. */
sealed interface SoftFilter {
    /** The query words it came from (they may also be title words: "2012", "Tamil Padam"). */
    val words: List<String>

    data class Year(val year: Int, override val words: List<String>) : SoftFilter
    data class Season(val season: Int, val episode: Int?, override val words: List<String>) : SoftFilter
    data class Episode(val episode: Int, override val words: List<String>) : SoftFilter
    data class Resolution(val min: Int, override val words: List<String>) : SoftFilter
    data class Quality(val quality: String, override val words: List<String>) : SoftFilter
    data class Language(val code: String, override val words: List<String>) : SoftFilter
    data class Genre(val genre: String, override val words: List<String>) : SoftFilter
    data class Kind(val kind: ItemKind, override val words: List<String>) : SoftFilter
    data class Watched(val state: WatchState, override val words: List<String>) : SoftFilter
    data class Provider(val provider: String, override val words: List<String>) : SoftFilter
}

data class ParsedQuery(
    /** Normalized significant words that must match text (title, file names, chats …). */
    val terms: List<String>,
    val soft: List<SoftFilter>,
    /** Hard filters written as operators (`lang:ta`, `year:2020-2023`, `in:Kids Channel` …). */
    val operators: SearchFilters,
    /** Chat name words from `in:` (matched against chat titles). */
    val chatWords: List<String>,
    /** The whole query, normalized (for exact-title detection). */
    val normalized: String,
) {
    val isEmpty: Boolean get() = terms.isEmpty() && soft.isEmpty() && chatWords.isEmpty()
}

enum class MatchField { TITLE, ALT_TITLE, FILE_NAME, CHAT, EXTRA }

data class TermHit(val queryTerm: String, val matched: String, val kind: TermMatchKind, val field: MatchField)

data class SearchHit(
    val itemId: Long,
    val kind: ItemKind,
    val title: String,
    val year: Int?,
    val score: Double,
    val hits: List<TermHit>,
    /** Soft filters this item satisfied. */
    val satisfied: List<SoftFilter>,
    /** "s2" / "s2e5" in the query: the season (and episode id, if it exists) to open. */
    val season: Int? = null,
    val episodeId: Long? = null,
    val episodeNumber: Int? = null,
)

data class SearchPage(
    val hits: List<SearchHit>,
    val total: Int,
    val offset: Int,
    val nextOffset: Int?,
    /** How the query was read ("ta = Tamil (language)", "doremon → doraemon"), for diagnostics. */
    val interpretation: List<String>,
    val tookMicros: Long,
)
