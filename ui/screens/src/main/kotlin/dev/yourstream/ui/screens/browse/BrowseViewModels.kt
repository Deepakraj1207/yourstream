package dev.yourstream.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MovieRepository
import dev.yourstream.library.api.SeriesRepository
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.Tile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Paged library list with filters. Filters survive process death (SavedStateHandle); the list
 * itself comes from the domain repository for the current [LibraryQuery].
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BrowseViewModel(
    private val saved: SavedStateHandle,
    private val fixedKind: LibraryKind?,
    private val source: (LibraryQuery) -> Flow<PagingData<LibraryItemSummary>>,
) : ViewModel() {
    private val _state = MutableStateFlow(
        BrowseUiState(
            filters = BrowseFilters(
                kind = saved.get<String>(KIND)?.let { LibraryKind.valueOf(it) },
                sort = saved.get<String>(SORT)?.let { LibrarySort.valueOf(it) } ?: LibrarySort.RECENTLY_ADDED,
                watch = saved.get<String>(WATCH)?.let { WatchFilter.valueOf(it) } ?: WatchFilter.ANY,
            ),
            showKindFilter = fixedKind == null,
        ),
    )
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    val items: Flow<PagingData<Tile>> = _state.map { it.filters }.distinctUntilChanged()
        .flatMapLatest { f -> source(f.toQuery(fixedKind)) }
        .map { page -> page.map(Presentation::tile) }
        .cachedIn(viewModelScope)

    fun onAction(action: BrowseAction) {
        _state.update { s ->
            val f = when (action) {
                is BrowseAction.SetKind -> s.filters.copy(kind = action.kind)
                is BrowseAction.SetSort -> s.filters.copy(sort = action.sort)
                is BrowseAction.SetWatch -> s.filters.copy(watch = action.watch)
            }
            saved[KIND] = f.kind?.name; saved[SORT] = f.sort.name; saved[WATCH] = f.watch.name
            s.copy(filters = f)
        }
    }

    private companion object { const val KIND = "kind"; const val SORT = "sort"; const val WATCH = "watch" }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(saved: SavedStateHandle, media: MediaRepository) :
    BrowseViewModel(saved, null, { media.search(it) })

@HiltViewModel
class MoviesViewModel @Inject constructor(saved: SavedStateHandle, movies: MovieRepository) :
    BrowseViewModel(saved, LibraryKind.MOVIE, { movies.movies(it) })

@HiltViewModel
class SeriesViewModel @Inject constructor(saved: SavedStateHandle, series: SeriesRepository) :
    BrowseViewModel(saved, LibraryKind.SERIES, { series.series(it) })
