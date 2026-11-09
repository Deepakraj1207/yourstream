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
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.Tile
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    /** Null while the query is blank (show the hint, not "no results"). */
    val results: UiState<List<Tile>>? = null,
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
class SearchViewModel @Inject constructor(saved: SavedStateHandle, private val search: LibrarySearch) : ViewModel() {
    private val query = saved.getMutableStateFlow(QUERY, "")

    private data class Results(val results: UiState<List<Tile>>?, val suggestions: List<String>, val interpretation: List<String>)

    private val results = query.debounce(DEBOUNCE_MS).distinctUntilChanged().flatMapLatest { q ->
        flow {
            if (q.isBlank()) { emit(Results(null, emptyList(), emptyList())); return@flow }
            emit(Results(UiState.Loading, emptyList(), emptyList()))
            emit(
                runCatching {
                    val page = search.search(SearchRequest(q, limit = 60))
                    Results(page.hits.map(Presentation::tile).toUiState(EmptyKind.NO_RESULTS), search.suggest(q, 6), page.interpretation)
                }.getOrElse { Results(UiState.Error(it.toUiError()), emptyList(), emptyList()) },
            )
        }
    }

    val state: StateFlow<SearchUiState> = combine(query, results) { q, r -> SearchUiState(q, r.results, r.suggestions, r.interpretation) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(query.value))

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> query.value = action.text
            is SearchAction.PickSuggestion -> query.value = query.value.substringBeforeLast(' ', "").let { if (it.isEmpty()) action.text else "$it ${action.text}" }
            SearchAction.Clear -> query.value = ""
        }
    }

    companion object {
        const val DEBOUNCE_MS = 250L
        private const val QUERY = "query"
    }
}
