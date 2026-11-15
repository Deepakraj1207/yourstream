package dev.yourstream.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.search.core.SearchRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.search.core.WatchState
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.assistant.engine.MediaAssistant
import dev.yourstream.assistant.engine.AssistantResponse
import dev.yourstream.assistant.core.Interpretation
import dev.yourstream.assistant.core.Intent
import dev.yourstream.search.engine.LibrarySearch
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.screens.ArtworkLookup
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.Tile
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The engine's best match first (when it clearly leads), then everything else. */
data class SearchResults(val top: Tile?, val rest: List<Tile>, val total: Int)

/**
 * Media Brain's answer to a request ("unwatched Tamil movies", "what should I watch", "play ben 10
 * season 2"): the domain's own sentence, what it understood, and its results. Local and
 * deterministic; no network.
 */
data class BrainAnswer(
    val message: String,
    /** What was recognised ("language Tamil", "unwatched"), from the parser's notes. */
    val understood: List<String>,
    val tiles: List<Tile>,
    val total: Int,
    /** A play request resolved to one thing: one-click play. */
    val play: OpenTarget.Play? = null,
)

data class SearchUiState(
    val query: String = "",
    /** Null while the query is blank (show suggestions, not "no results"). */
    val results: UiState<SearchResults>? = null,
    val suggestions: List<String> = emptyList(),
    /** How the engine read the query ("ta = Tamil", "doremon → doraemon"), from the domain. */
    val interpretation: List<String> = emptyList(),
    /** Set when the query is a request rather than a bare title. */
    val brain: BrainAnswer? = null,
    /** The add-ons' answer, one row per add-on and type; null: not asked (blank or one letter). */
    val addons: dev.yourstream.ui.screens.AddonSections? = null,
    val addonsLoading: Boolean = false,
    /** Recent searches, newest first (shown while the field is focused and empty). */
    val recent: List<String> = emptyList(),
    val discover: DiscoverUiState = DiscoverUiState(),
)

/** Search → Discover: browse an add-on catalog by type, catalog and genre. */
data class DiscoverUiState(
    val catalogs: List<dev.yourstream.ui.screens.DiscoverCatalog> = emptyList(),
    val loadingCatalogs: Boolean = true,
    val type: dev.yourstream.catalog.api.CatalogType? = null,
    val catalog: dev.yourstream.ui.screens.DiscoverCatalog? = null,
    /** Null: all genres. */
    val genre: String? = null,
    val tiles: List<Tile> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val nextSkip: Int? = null,
    val failed: Boolean = false,
) {
    val types: List<dev.yourstream.catalog.api.CatalogType> get() = catalogs.map { it.type }.distinct()
    val catalogsOfType: List<dev.yourstream.ui.screens.DiscoverCatalog> get() = catalogs.filter { it.type == type }
}

sealed interface SearchAction {
    data class QueryChanged(val text: String) : SearchAction
    data class PickSuggestion(val text: String) : SearchAction
    data object Clear : SearchAction
    /** The search was meant (keyboard search, or a result opened): remember it. */
    data object Submit : SearchAction
    data class PickRecent(val text: String) : SearchAction
    data class RemoveRecent(val text: String) : SearchAction
    data object Retry : SearchAction
    data class DiscoverType(val type: dev.yourstream.catalog.api.CatalogType) : SearchAction
    data class DiscoverCatalog(val key: String) : SearchAction
    data class DiscoverGenre(val genre: String?) : SearchAction
    data object DiscoverMore : SearchAction
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val search: LibrarySearch,
    private val artwork: ArtworkLookup,
    private val assistant: MediaAssistant,
    private val addonSearch: dev.yourstream.ui.screens.AddonSearch,
    private val memory: SearchMemory,
) : ViewModel() {
    private val query = saved.getMutableStateFlow(QUERY, "")
    private val retry = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val recent = kotlinx.coroutines.flow.MutableStateFlow(memory.recent())
    private val discover = kotlinx.coroutines.flow.MutableStateFlow(DiscoverUiState())
    private var discoverJob: kotlinx.coroutines.Job? = null

    private data class Out(val results: UiState<SearchResults>?, val suggestions: List<String>, val interpretation: List<String>, val brain: BrainAnswer? = null)

    private val results = combine(query.debounce(DEBOUNCE_MS), retry) { q, n -> q to n }.distinctUntilChanged().flatMapLatest { (q, _) ->
        flow {
            if (q.isBlank()) { emit(Out(null, emptyList(), emptyList())); return@flow }
            emit(Out(UiState.Loading, emptyList(), emptyList()))
            emit(
                runCatching {
                    coroutineScope {
                        // The request reading and the title search run side by side: a title that
                        // looks like a filter ("1917") is still found by name.
                        val asked = async { runCatching { assistant.ask(q) }.getOrNull() }
                        val page = search.search(SearchRequest(q, limit = 60))
                        val brain = asked.await()?.takeIf { MediaBrain.isRequest(it.interpretation) }?.let { brainAnswer(it) }
                        val shown = brain?.tiles.orEmpty().mapNotNull { it.itemId }.toSet()
                        val hits = page.hits.filter { it.itemId !in shown || it.episodeId != null }
                        // Artwork for what is on screen first; the engine's order is kept.
                        val tiles = hits.mapIndexed { i, h -> Presentation.tile(h, if (i < ARTWORK_FOR) artwork.poster(h.itemId) else null) }
                        val leads = brain == null && (hits.size == 1 || (hits.size > 1 && hits[0].score >= hits[1].score * TOP_MARGIN))
                        val r: UiState<SearchResults> = if (tiles.isEmpty() && brain?.tiles.isNullOrEmpty()) UiState.Empty(EmptyKind.NO_RESULTS)
                        else UiState.Content(SearchResults(tiles.firstOrNull()?.takeIf { leads }, if (leads) tiles.drop(1) else tiles, page.total))
                        Out(r, search.suggest(q, 6).filter { it != q.trim().lowercase() }, page.interpretation, brain)
                    }
                }.getOrElse { Out(UiState.Error(it.toUiError()), emptyList(), emptyList()) },
            )
        }
    }

    private suspend fun brainAnswer(r: AssistantResponse): BrainAnswer {
        val tiles = r.items.mapIndexed { i, a -> Presentation.tile(a, if (i < ARTWORK_FOR) artwork.poster(a.itemId) else null) }
        val play = (tiles.singleOrNull()?.open as? OpenTarget.Play)?.takeIf { r.interpretation.intent == Intent.PLAY }
            ?: tiles.singleOrNull()?.takeIf { r.interpretation.intent == Intent.PLAY }?.itemId?.let { OpenTarget.Play(PlaybackTarget(it)) }
        return BrainAnswer(r.message, MediaBrain.understood(r.interpretation), tiles, r.total, play)
    }

    private data class Addons(val q: String, val sections: dev.yourstream.ui.screens.AddonSections?, val loading: Boolean)

    /** Network search in the add-ons, after typing pauses a little longer than the local one. */
    private val addonHits = combine(query.debounce(ADDON_DEBOUNCE_MS), retry) { q, n -> q.trim() to n }.distinctUntilChanged().flatMapLatest { (q, _) ->
        flow {
            if (q.length < 2) { emit(Addons(q, null, false)); return@flow }
            emit(Addons(q, null, true))
            emit(Addons(q, runCatching { addonSearch.sections(q) }.getOrElse { dev.yourstream.ui.screens.AddonSections(emptyList(), failed = true) }, false))
        }
    }.onStart { emit(Addons("", null, false)) }

    val state: StateFlow<SearchUiState> = combine(query, results, addonHits, recent, discover) { q, r, a, rec, d ->
        // Add-on answers for an older query are not shown under a new one.
        val fresh = a.q == q.trim()
        SearchUiState(q, r.results, r.suggestions, r.interpretation, r.brain,
            a.sections.takeIf { fresh && q.isNotBlank() }, (a.loading || !fresh) && q.trim().length >= 2, rec, d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(query.value, recent = recent.value))

    init { loadDiscover() }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> query.value = action.text
            is SearchAction.PickSuggestion -> query.value = query.value.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) action.text else "$it ${action.text}" }
            SearchAction.Clear -> query.value = ""
            SearchAction.Submit -> remember(query.value)
            is SearchAction.PickRecent -> { query.value = action.text; remember(action.text) }
            is SearchAction.RemoveRecent -> saveRecent(recent.value.filterNot { it.equals(action.text, ignoreCase = true) })
            SearchAction.Retry -> retry.value++
            is SearchAction.DiscoverType -> choose(DiscoverChoice(action.type))
            is SearchAction.DiscoverCatalog -> choose(DiscoverChoice(discover.value.type, action.key))
            is SearchAction.DiscoverGenre -> choose(DiscoverChoice(discover.value.type, discover.value.catalog?.key, action.genre))
            SearchAction.DiscoverMore -> loadMore()
        }
    }

    /** Recent searches: newest first, no repeats (any case), at most [RECENT_MAX]. */
    private fun remember(text: String) {
        val q = text.trim().takeIf { it.isNotEmpty() } ?: return
        saveRecent((listOf(q) + recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(RECENT_MAX))
    }

    private fun saveRecent(list: List<String>) { recent.value = list; memory.saveRecent(list) }

    private fun loadDiscover() {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val catalogs = runCatching { addonSearch.catalogs() }.getOrDefault(emptyList())
            discover.value = DiscoverUiState(catalogs, loadingCatalogs = false)
            if (catalogs.isNotEmpty()) choose(memory.discover())
        }
    }

    /**
     * Applies [want] where it fits what the add-ons offer: an unknown type falls back to the
     * first, a catalog of another type to that type's first, a genre the catalog lacks to all
     * genres (or its first, when it requires one). Then loads the first page.
     */
    private fun choose(want: DiscoverChoice) {
        val d = discover.value
        val type = want.type?.takeIf { it in d.types } ?: d.types.firstOrNull() ?: return
        val catalog = d.catalogs.firstOrNull { it.key == want.catalog && it.type == type } ?: d.catalogs.first { it.type == type }
        val genre = want.genre?.takeIf { it in catalog.genres } ?: catalog.genres.firstOrNull()?.takeIf { catalog.genreRequired }
        memory.saveDiscover(DiscoverChoice(type, catalog.key, genre))
        discover.value = d.copy(type = type, catalog = catalog, genre = genre, tiles = emptyList(), loading = true, loadingMore = false, nextSkip = null, failed = false)
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val page = runCatching { addonSearch.page(catalog, genre, 0) }
            discover.value = discover.value.copy(tiles = page.getOrNull()?.tiles.orEmpty(), nextSkip = page.getOrNull()?.nextSkip, loading = false, failed = page.isFailure)
        }
    }

    private fun loadMore() {
        val d = discover.value
        val skip = d.nextSkip ?: return
        val catalog = d.catalog ?: return
        if (d.loading || d.loadingMore) return
        discover.value = d.copy(loadingMore = true)
        discoverJob = viewModelScope.launch {
            val page = runCatching { addonSearch.page(catalog, d.genre, skip) }.getOrNull()
            val now = discover.value
            val seen = now.tiles.map { it.card.key }.toSet()
            discover.value = now.copy(tiles = now.tiles + page?.tiles.orEmpty().filter { it.card.key !in seen }, nextSkip = page?.nextSkip, loadingMore = false)
        }
    }

    companion object {
        const val DEBOUNCE_MS = 250L
        const val ADDON_DEBOUNCE_MS = 600L
        const val RECENT_MAX = 10
        private const val QUERY = "query"
        private const val ARTWORK_FOR = 24
        /** The first hit is a "top result" when it scores this much above the second. */
        private const val TOP_MARGIN = 1.25
    }
}

/** When the local assistant should answer a search, and how to show what it understood. Pure, tested. */
object MediaBrain {
    /**
     * A request, not a bare title: an explicit intent ("what should I watch", "continue", "play …",
     * "… episodes"), or at least one filter the parser recognised (type, language, genre, years,
     * season/episode, watched state, sort, count, resolution).
     */
    fun isRequest(i: Interpretation): Boolean {
        if (i.intent != Intent.SEARCH) return true
        val e = i.entities
        return e.kinds.isNotEmpty() || e.languages.isNotEmpty() || e.genres.isNotEmpty() || e.yearFrom != null || e.yearTo != null ||
            e.season != null || e.episode != null || e.watch != WatchState.ANY || e.sort != null || e.limit != null || e.minResolution != null
    }

    /** "telugu → language Telugu" → "language Telugu". */
    fun understood(i: Interpretation): List<String> = i.notes.map { it.substringAfter("→").trim() }.filter { it.isNotEmpty() }.distinct()
}
