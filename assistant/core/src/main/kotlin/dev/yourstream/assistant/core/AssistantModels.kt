package dev.yourstream.assistant.core

import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.WatchState

enum class Intent {
    /** "show Tamil movies", "find movies from 2005". */
    SEARCH,
    /** "find Doraemon episodes", "find season 2 episode 10". */
    EPISODES,
    /** "continue watching Ben 10", "resume". */
    CONTINUE_WATCHING,
    /** "what should I watch next?", "suggest a comedy". */
    RECOMMEND,
    /** "play Doraemon season 1 episode 3". */
    PLAY,
}

/** Everything extracted from the request. Null / empty = not mentioned. */
data class Entities(
    /** Words left after removing commands, filters and filler: the title the user means. */
    val title: String? = null,
    val kinds: Set<ItemKind> = emptySet(),
    /** Normalized language codes. */
    val languages: Set<String> = emptySet(),
    /** Normalized genre names (as the search index stores them). */
    val genres: Set<String> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val watch: WatchState = WatchState.ANY,
    val sort: SearchSort? = null,
    val reversed: Boolean = false,
    val limit: Int? = null,
    val minResolution: Int? = null,
)

enum class InterpretationSource { DETERMINISTIC, MODEL }

data class Interpretation(
    val text: String,
    val intent: Intent,
    val entities: Entities,
    /** 0..1: how sure the parser is (a recognised command and entities score higher than bare words). */
    val confidence: Double,
    val source: InterpretationSource = InterpretationSource.DETERMINISTIC,
    /** Human-readable trace of what was recognised ("telugu → language Telugu"). */
    val notes: List<String> = emptyList(),
)

/** What to run against the local database. */
sealed interface QueryPlan {
    /** Library search with hard filters (title words as text). */
    data class Search(val request: SearchRequest) : QueryPlan

    /** Episodes of the series matching [seriesTitle] (all series when null). */
    data class Episodes(
        val seriesTitle: String?,
        val season: Int?,
        val episode: Int?,
        val watch: WatchState,
        val languages: Set<String>,
        val limit: Int,
    ) : QueryPlan

    /** In-progress items (optionally of one title), else that title's next unwatched episode. */
    data class ContinueWatching(val title: String?, val limit: Int) : QueryPlan

    /** Continue watching first, then unwatched titles fitting the user's habits and the request. */
    data class Recommend(val languages: Set<String>, val genres: Set<String>, val kinds: Set<ItemKind>, val limit: Int) : QueryPlan

    /** Resolve one thing to play: a title, optionally a season/episode. */
    data class Play(val title: String, val season: Int?, val episode: Int?) : QueryPlan
}
