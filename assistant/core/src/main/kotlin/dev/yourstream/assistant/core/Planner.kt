package dev.yourstream.assistant.core

import dev.yourstream.search.core.SearchFilters
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.core.SearchSort

/** Interpretation → query plan. Pure: the plan says *what* to read, the engine reads it. */
object Planner {
    const val DEFAULT_LIMIT = 30

    fun plan(i: Interpretation): QueryPlan {
        val e = i.entities
        return when (i.intent) {
            Intent.SEARCH -> QueryPlan.Search(searchRequest(e))
            Intent.EPISODES -> QueryPlan.Episodes(e.title, e.season, e.episode, e.watch, e.languages, e.limit ?: 100)
            Intent.CONTINUE_WATCHING -> QueryPlan.ContinueWatching(e.title, e.limit ?: 20)
            Intent.RECOMMEND -> QueryPlan.Recommend(e.languages, e.genres, e.kinds, e.limit ?: 10)
            // "play" without a title cannot resolve anything: search instead.
            Intent.PLAY -> e.title?.let { QueryPlan.Play(it, e.season, e.episode) } ?: QueryPlan.Search(searchRequest(e))
        }
    }

    fun searchRequest(e: Entities): SearchRequest = SearchRequest(
        text = e.title.orEmpty(),
        filters = SearchFilters(
            kinds = e.kinds,
            languages = e.languages,
            genres = e.genres,
            yearFrom = e.yearFrom,
            yearTo = e.yearTo,
            season = e.season,
            minResolution = e.minResolution,
            watch = e.watch,
        ),
        // Without title words there is nothing to rank by relevance: newest first.
        sort = e.sort ?: if (e.title == null) SearchSort.RECENTLY_ADDED else SearchSort.RELEVANCE,
        reversed = e.reversed,
        limit = e.limit ?: DEFAULT_LIMIT,
    )
}
