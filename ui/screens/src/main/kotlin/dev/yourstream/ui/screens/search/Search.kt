package dev.yourstream.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.search.core.SearchRequest
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
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The engine's best match first (when it clearly leads), then everything else. */
data class SearchResults(val top: Tile?, val rest: List<Tile>, val total: Int)

data class SearchUiState(
    val query: String = "",
    /** Null while the query is blank (show suggestions, not "no results"). */
    val results: UiState<SearchResults>? = null,
    val suggestions: List<String> = emptyList(),
    /** How the engine read the query ("ta = Tamil", "doremon → doraemon"), from the domain. */
    val interpretation: List<String> = emptyList(),
)

sealed interface SearchAction {
    data class QueryChanged(val text: String) : SearchAction
    data class PickSuggestion(val text: String) : SearchAction
    data object Clear : SearchAction
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val search: LibrarySearch,
    private val artwork: ArtworkLookup,
) : ViewModel() {
    private val query = saved.getMutableStateFlow(QUERY, "")

    private data class Out(val results: UiState<SearchResults>?, val suggestions: List<String>, val interpretation: List<String>)

    private val results = query.debounce(DEBOUNCE_MS).distinctUntilChanged().flatMapLatest { q ->
        flow {
            if (q.isBlank()) { emit(Out(null, emptyList(), emptyList())); return@flow }
            emit(Out(UiState.Loading, emptyList(), emptyList()))
            emit(
                runCatching {
                    val page = search.search(SearchRequest(q, limit = 60))
                    // Artwork for what is on screen first; the engine's order is kept.
                    val tiles = page.hits.mapIndexed { i, h -> Presentation.tile(h, if (i < ARTWORK_FOR) artwork.poster(h.itemId) else null) }
                    val leads = page.hits.size == 1 || (page.hits.size > 1 && page.hits[0].score >= page.hits[1].score * TOP_MARGIN)
                    val r: UiState<SearchResults> = if (tiles.isEmpty()) UiState.Empty(EmptyKind.NO_RESULTS)
                    else UiState.Content(SearchResults(tiles.first().takeIf { leads }, if (leads) tiles.drop(1) else tiles, page.total))
                    Out(r, search.suggest(q, 6).filter { it != q.trim().lowercase() }, page.interpretation)
                }.getOrElse { Out(UiState.Error(it.toUiError()), emptyList(), emptyList()) },
            )
        }
    }

    val state: StateFlow<SearchUiState> = combine(query, results) { q, r -> SearchUiState(q, r.results, r.suggestions, r.interpretation) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(query.value))

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> query.value = action.text
            is SearchAction.PickSuggestion -> query.value = query.value.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) action.text else "$it ${action.text}" }
            SearchAction.Clear -> query.value = ""
        }
    }

    companion object {
        const val DEBOUNCE_MS = 250L
        private const val QUERY = "query"
        private const val ARTWORK_FOR = 24
        /** The first hit is a "top result" when it scores this much above the second. */
        private const val TOP_MARGIN = 1.25
    }
}
