package dev.yourstream.ui.screens.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.PagedContent
import dev.yourstream.ui.core.PagedMediaGrid
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.Segmented
import dev.yourstream.ui.core.SortMenu
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.tileActions
import dev.yourstream.ui.screens.tileCard

@Composable
fun LibraryRoute(onOpen: (OpenTarget) -> Unit, vm: LibraryViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_library, EmptyKind.LIBRARY_EMPTY, vm, onOpen)

@Composable
fun MoviesRoute(onOpen: (OpenTarget) -> Unit, vm: MoviesViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_movies, EmptyKind.LIBRARY_EMPTY, vm, onOpen)

@Composable
fun SeriesRoute(onOpen: (OpenTarget) -> Unit, vm: SeriesViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_series, EmptyKind.LIBRARY_EMPTY, vm, onOpen)

@Composable
fun RecentlyAddedRoute(onOpen: (OpenTarget) -> Unit, vm: RecentlyAddedViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_recent, EmptyKind.LIBRARY_EMPTY, vm, onOpen)

@Composable
fun FavoritesRoute(onOpen: (OpenTarget) -> Unit, vm: FavoritesViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_favorites, EmptyKind.NO_FAVORITES, vm, onOpen)

@Composable
private fun BrowseRoute(@StringRes title: Int, empty: EmptyKind, vm: BrowseViewModel, onOpen: (OpenTarget) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    BrowseScreen(stringResource(title), empty, state, vm.items.collectAsLazyPagingItems(), vm::onAction, onOpen)
}

/**
 * A library grid: title with count, filters (scroll away with the grid), posters. Empty: [empty]
 * (or "no results" when filters are set). Loading: a skeleton grid.
 */
@Composable
fun BrowseScreen(title: String, empty: EmptyKind, state: BrowseUiState, items: LazyPagingItems<Tile>, onAction: (BrowseAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    // Nothing to filter: an unfiltered, empty list shows just its empty state (no "0 titles", no chips).
    val nothing = state.count == 0 && !state.filters.isFiltered
    val count = state.count?.takeUnless { nothing }?.let { pluralStringResource(R.plurals.titles_count, it, it) }
    val actions = tileActions({ onAction(BrowseAction.Tile(it)) }, onOpen)
    val card = tileCard()
    ScreenScaffold(title, count = count) {
        Column {
            if (!nothing) Filters(state, onAction)
            PagedContent(items, if (state.filters.isFiltered) EmptyKind.NO_RESULTS else empty) { paged ->
                PagedMediaGrid(paged, card, { onOpen(it.open) }, actions = actions)
            }
        }
    }
}

@Composable
private fun Filters(state: BrowseUiState, onAction: (BrowseAction) -> Unit) {
    val c = state.controls
    if (!c.kind && !c.sort && !c.watch) return
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (c.kind) {
            Segmented(
                listOf(ChipOption(null, stringResource(R.string.filter_all)), ChipOption(LibraryKind.MOVIE, stringResource(R.string.nav_movies)),
                    ChipOption(LibraryKind.SERIES, stringResource(R.string.nav_series)), ChipOption(LibraryKind.VIDEO, stringResource(R.string.filter_videos))),
                state.filters.kind, { onAction(BrowseAction.SetKind(it)) },
            )
        }
        if (c.sort || c.watch) Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.sort) SortMenu(
                stringResource(R.string.sort_label),
                listOf(ChipOption(LibrarySort.RECENTLY_ADDED, stringResource(R.string.sort_added)), ChipOption(LibrarySort.TITLE, stringResource(R.string.sort_title)),
                    ChipOption(LibrarySort.YEAR, stringResource(R.string.sort_year)), ChipOption(LibrarySort.RECENTLY_WATCHED, stringResource(R.string.sort_watched)),
                    ChipOption(LibrarySort.SIZE, stringResource(R.string.sort_size))),
                state.filters.sort, { onAction(BrowseAction.SetSort(it)) }, Modifier.padding(start = LocalDimens.current.gutter),
            )
            if (c.watch) ChipRow(
                listOf(ChipOption(WatchFilter.UNWATCHED, stringResource(R.string.watch_unwatched)),
                    ChipOption(WatchFilter.IN_PROGRESS, stringResource(R.string.watch_in_progress)), ChipOption(WatchFilter.WATCHED, stringResource(R.string.watch_watched))),
                state.filters.watch.takeIf { it != WatchFilter.ANY },
                { w -> onAction(BrowseAction.SetWatch(if (w == state.filters.watch) WatchFilter.ANY else w)) },
            )
        }
    }
}
