package dev.yourstream.ui.screens.browse

import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.ui.screens.TileAction

/** Filters of a library list; maps 1:1 to the domain's [LibraryQuery]. */
data class BrowseFilters(
    /** Null = all kinds (Library / Recently added only; Movies/Series are fixed). */
    val kind: LibraryKind? = null,
    val sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
    val watch: WatchFilter = WatchFilter.ANY,
) {
    fun toQuery(fixedKind: LibraryKind?): LibraryQuery =
        LibraryQuery(kinds = setOfNotNull(fixedKind ?: kind), sort = sort, watch = watch)

    val isFiltered: Boolean get() = kind != null || watch != WatchFilter.ANY
}

/** Which controls a browse screen shows. */
data class BrowseControls(val kind: Boolean, val sort: Boolean, val watch: Boolean)

data class BrowseUiState(
    val filters: BrowseFilters = BrowseFilters(),
    val controls: BrowseControls = BrowseControls(kind = true, sort = true, watch = true),
    /** Titles matching the filters (null while counting). */
    val count: Int? = null,
)

sealed interface BrowseAction {
    data class SetKind(val kind: LibraryKind?) : BrowseAction
    data class SetSort(val sort: LibrarySort) : BrowseAction
    data class SetWatch(val watch: WatchFilter) : BrowseAction
    data class Tile(val action: TileAction) : BrowseAction
}
