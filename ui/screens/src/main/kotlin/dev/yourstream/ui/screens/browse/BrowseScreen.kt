package dev.yourstream.ui.screens.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.platform.testTag
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
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.PagedContent
import dev.yourstream.ui.core.PagedMediaGrid
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.MenuChip
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.tileActions
import dev.yourstream.ui.screens.tileCard

@Composable
fun LibraryRoute(onOpen: (OpenTarget) -> Unit, vm: LibraryViewModel = hiltViewModel()) = BrowseRoute(R.string.nav_library, EmptyKind.MY_LIBRARY_EMPTY, vm, onOpen)

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
 * A library grid: title with count, one row of filter chips, posters. Empty: [empty] (or "no
 * results" when filters are set, with the filters still there to undo them). Loading: skeletons.
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
            if (!nothing) FilterBar(state, onAction)
            PagedContent(items, if (state.filters.isFiltered) EmptyKind.NO_RESULTS else empty) { paged ->
                PagedMediaGrid(paged, card, { onOpen(it.open) }, actions = actions)
            }
        }
    }
}

/** Sort, watch status and kind in one horizontally scrolling row of same-height chips. */
@Composable
private fun FilterBar(state: BrowseUiState, onAction: (BrowseAction) -> Unit) {
    val c = state.controls
    if (!c.kind && !c.sort && !c.watch) return
    val f = state.filters
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = LocalDimens.current.gutter).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (c.sort) {
            val sorts = listOf(
                ChipOption(LibrarySort.RECENTLY_ADDED, stringResource(R.string.sort_added)), ChipOption(LibrarySort.TITLE, stringResource(R.string.sort_title)),
                ChipOption(LibrarySort.YEAR, stringResource(R.string.sort_year)), ChipOption(LibrarySort.RECENTLY_WATCHED, stringResource(R.string.sort_watched)),
                ChipOption(LibrarySort.SIZE, stringResource(R.string.sort_size)),
            )
            MenuChip(sorts.first { it.value == f.sort }.label, sorts, f.sort, { onAction(BrowseAction.SetSort(it)) }, tag = "sort")
        }
        if (c.watch) {
            val statuses = listOf(
                ChipOption(WatchFilter.ANY, stringResource(R.string.watch_any)), ChipOption(WatchFilter.UNWATCHED, stringResource(R.string.watch_unwatched)),
                ChipOption(WatchFilter.IN_PROGRESS, stringResource(R.string.watch_in_progress)), ChipOption(WatchFilter.WATCHED, stringResource(R.string.watch_watched)),
            )
            val label = if (f.watch == WatchFilter.ANY) stringResource(R.string.watch_label) else statuses.first { it.value == f.watch }.label
            MenuChip(label, statuses, f.watch, { onAction(BrowseAction.SetWatch(it)) }, highlighted = f.watch != WatchFilter.ANY, tag = "watch")
        }
        if (c.kind) {
            if (c.sort || c.watch) VerticalDivider(Modifier.height(24.dp))
            listOf(
                ChipOption<LibraryKind?>(null, stringResource(R.string.filter_all)), ChipOption(LibraryKind.MOVIE, stringResource(R.string.nav_movies)),
                ChipOption(LibraryKind.SERIES, stringResource(R.string.nav_series)), ChipOption(LibraryKind.VIDEO, stringResource(R.string.filter_videos)),
            ).forEach { o ->
                FilterChip(
                    selected = f.kind == o.value, onClick = { onAction(BrowseAction.SetKind(o.value)) }, label = { Text(o.label, maxLines = 1) },
                    modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("segment:${o.label}"),
                )
            }
        }
    }
}
