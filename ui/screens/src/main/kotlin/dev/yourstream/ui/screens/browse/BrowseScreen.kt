package dev.yourstream.ui.screens.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import dev.yourstream.ui.core.PagedContent
import dev.yourstream.ui.core.PagedMediaGrid
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget

@Composable
fun LibraryRoute(onOpen: (OpenTarget) -> Unit, vm: LibraryViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_library, vm, onOpen)

@Composable
fun MoviesRoute(onOpen: (OpenTarget) -> Unit, vm: MoviesViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_movies, vm, onOpen)

@Composable
fun SeriesRoute(onOpen: (OpenTarget) -> Unit, vm: SeriesViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_series, vm, onOpen)

@Composable
private fun BrowseRoute(@StringRes title: Int, vm: BrowseViewModel, onOpen: (OpenTarget) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    BrowseScreen(stringResource(title), state, vm.items.collectAsLazyPagingItems(), vm::onAction, onOpen)
}

@Composable
fun BrowseScreen(title: String, state: BrowseUiState, items: LazyPagingItems<Tile>, onAction: (BrowseAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    ScreenScaffold(title) {
        Column {
            if (state.showKindFilter) {
                ChipRow(
                    listOf(ChipOption(null, stringResource(R.string.filter_all)), ChipOption(LibraryKind.MOVIE, stringResource(R.string.nav_movies)),
                        ChipOption(LibraryKind.SERIES, stringResource(R.string.nav_series)), ChipOption(LibraryKind.VIDEO, stringResource(R.string.filter_videos))),
                    state.filters.kind, { onAction(BrowseAction.SetKind(it)) },
                )
            }
            ChipRow(
                listOf(ChipOption(LibrarySort.RECENTLY_ADDED, stringResource(R.string.sort_added)), ChipOption(LibrarySort.TITLE, stringResource(R.string.sort_title)),
                    ChipOption(LibrarySort.YEAR, stringResource(R.string.sort_year)), ChipOption(LibrarySort.RECENTLY_WATCHED, stringResource(R.string.sort_watched))),
                state.filters.sort, { onAction(BrowseAction.SetSort(it)) },
            )
            ChipRow(
                listOf(ChipOption(WatchFilter.ANY, stringResource(R.string.filter_all)), ChipOption(WatchFilter.UNWATCHED, stringResource(R.string.watch_unwatched)),
                    ChipOption(WatchFilter.IN_PROGRESS, stringResource(R.string.watch_in_progress)), ChipOption(WatchFilter.WATCHED, stringResource(R.string.watch_watched))),
                state.filters.watch, { onAction(BrowseAction.SetWatch(it)) },
            )
            Spacer(Modifier.height(8.dp))
            PagedContent(items, if (state.filters.isFiltered) EmptyKind.NO_RESULTS else EmptyKind.LIBRARY_EMPTY) { paged ->
                PagedMediaGrid(paged, Tile::card, { onOpen(it.open) })
            }
        }
    }
}

