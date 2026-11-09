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
import dev.yourstream.ui.screens.TileActionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Paged library list with filters and a live count. Filters survive process death
 * (SavedStateHandle); the list comes from the domain repository for the current [LibraryQuery].
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BrowseViewModel(
    private val saved: SavedStateHandle,
    private val fixedKind: LibraryKind?,
    controls: BrowseControls,
    defaultSort: LibrarySort,
    private val media: MediaRepository,
    private val actions: TileActionHandler,
    private val source: (LibraryQuery) -> Flow<PagingData<LibraryItemSummary>>,
) : ViewModel() {
    private val filters = MutableStateFlow(
        BrowseFilters(
            kind = saved.get<String>(KIND)?.let { LibraryKind.valueOf(it) },
            sort = saved.get<String>(SORT)?.let { LibrarySort.valueOf(it) } ?: defaultSort,
            watch = saved.get<String>(WATCH)?.let { WatchFilter.valueOf(it) } ?: WatchFilter.ANY,
        ),
    )

    // The previous count stays until the new one is known, so the title does not blink on each filter change.
    private val count: Flow<Int?> = filters.flatMapLatest { f -> media.count(query(f)) }.distinctUntilChanged().map<Int, Int?> { it }.catch { emit(null) }

    val state: StateFlow<BrowseUiState> = combine(filters, count) { f, n -> BrowseUiState(f, controls, n) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState(filters.value, controls))

    val items: Flow<PagingData<Tile>> = filters
        .flatMapLatest { f -> source(query(f)) }
        .map { page -> page.map(Presentation::tile) }
        .cachedIn(viewModelScope)

    protected open fun query(f: BrowseFilters): LibraryQuery = f.toQuery(fixedKind)

    fun onAction(action: BrowseAction) {
        if (action is BrowseAction.Tile) { viewModelScope.launch { actions.run(action.action) }; return }
        filters.update { f ->
            when (action) {
                is BrowseAction.SetKind -> f.copy(kind = action.kind)
                is BrowseAction.SetSort -> f.copy(sort = action.sort)
                is BrowseAction.SetWatch -> f.copy(watch = action.watch)
                is BrowseAction.Tile -> f
            }.also { n -> saved[KIND] = n.kind?.name; saved[SORT] = n.sort.name; saved[WATCH] = n.watch.name }
        }
    }

    private companion object { const val KIND = "kind"; const val SORT = "sort"; const val WATCH = "watch" }
}

private val ALL = BrowseControls(kind = true, sort = true, watch = true)
private val FIXED_KIND = BrowseControls(kind = false, sort = true, watch = true)

@HiltViewModel
class LibraryViewModel @Inject constructor(saved: SavedStateHandle, media: MediaRepository, actions: TileActionHandler) :
    BrowseViewModel(saved, null, ALL, LibrarySort.TITLE, media, actions, { media.search(it) }) {
    /** "My library": only what the user added (empty until they add a movie or series). */
    override fun query(f: BrowseFilters) = f.toQuery(null).copy(myLibraryOnly = true)
}

@HiltViewModel
class MoviesViewModel @Inject constructor(saved: SavedStateHandle, movies: MovieRepository, media: MediaRepository, actions: TileActionHandler) :
    BrowseViewModel(saved, LibraryKind.MOVIE, FIXED_KIND, LibrarySort.RECENTLY_ADDED, media, actions, { movies.movies(it) })

@HiltViewModel
class SeriesViewModel @Inject constructor(saved: SavedStateHandle, series: SeriesRepository, media: MediaRepository, actions: TileActionHandler) :
    BrowseViewModel(saved, LibraryKind.SERIES, FIXED_KIND, LibrarySort.RECENTLY_ADDED, media, actions, { series.series(it) })

/** Newest arrivals ("a new episode bumps its series"), by kind: `MediaRepository.recentlyAdded`. */
@HiltViewModel
class RecentlyAddedViewModel @Inject constructor(saved: SavedStateHandle, media: MediaRepository, actions: TileActionHandler) :
    BrowseViewModel(saved, null, BrowseControls(kind = true, sort = false, watch = false), LibrarySort.RECENTLY_ADDED, media, actions,
        { q -> media.recentlyAdded(q.kinds) }) {
    override fun query(f: BrowseFilters) = LibraryQuery(kinds = setOfNotNull(f.kind), sort = LibrarySort.RECENTLY_ADDED)
}

/** Favorites with filters off (the order is the user's: newest favorite first). */
@HiltViewModel
class FavoritesViewModel @Inject constructor(saved: SavedStateHandle, media: MediaRepository, actions: TileActionHandler) :
    BrowseViewModel(saved, null, BrowseControls(kind = true, sort = false, watch = false), LibrarySort.RECENTLY_ADDED, media, actions,
        { _ -> media.favorites() }) {
    override fun query(f: BrowseFilters) = LibraryQuery(kinds = setOfNotNull(f.kind), favoritesOnly = true)
}
