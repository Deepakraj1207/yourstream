package dev.yourstream.ui.screens.home

import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.TileAction
import dev.yourstream.ui.screens.navigation.OpenTarget

/** Something the user should do or know before the rows make sense. */
enum class HomeBanner { NOT_SIGNED_IN, NO_SOURCES, OFFLINE }

/** One catalog from an add-on, as a Home row. */
data class CatalogRow(
    val key: String,
    val title: String,
    val tiles: List<dev.yourstream.ui.screens.Tile>,
    /** What the catalog holds, for "Popular · Movies" (Settings → Layout → Show catalog type). */
    val type: dev.yourstream.catalog.api.CatalogType? = null,
    /** Cards of titles from future years (not out yet), for "Hide unreleased content". */
    val unreleased: Set<String> = emptySet(),
)

/** Unwatched picks from the local watch profile; [basis] says what it is built from. */
data class ForYou(val basis: String?, val tiles: List<dev.yourstream.ui.screens.Tile>)

/** The featured title. [kind] decides the wording; the targets come from the domain's entries. */
/**
 * One slide of Home's hero carousel (from the user's hero catalogs, Settings → Layout → Home
 * layout): artwork, the title, "Movie · Romance · 2h 11m · 2026", rating and languages.
 */
data class HeroSlide(
    val key: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val type: dev.yourstream.catalog.api.CatalogType,
    val genre: String?,
    val runtimeMinutes: Int?,
    val year: Int?,
    val rating: Double?,
    /** ISO codes ("ta"), shown as "TA". */
    val languages: List<String>,
    val open: OpenTarget,
)

data class HeroItem(
    val kind: Kind,
    val key: String,
    val title: String,
    val meta: String?,
    val posterUrl: String?,
    val progress: Float?,
    val primary: OpenTarget,
    val secondary: OpenTarget,
    /** "S01E06" for an episode to continue. */
    val episodeLabel: String? = null,
) {
    enum class Kind { CONTINUE, NEW }
}

data class HomeUiState(
    val banner: HomeBanner? = null,
    val hero: UiState<HeroItem> = UiState.Loading,
    val continueWatching: UiState<List<Tile>> = UiState.Loading,
    /** Titles in the library (null while unknown). */
    val libraryCount: Int? = null,
    val forYou: UiState<ForYou> = UiState.Loading,
    /** Chats being indexed right now (the status bar at the top). */
    val indexing: dev.yourstream.ui.screens.IndexingStatus = dev.yourstream.ui.screens.IndexingStatus(),
    val catalogs: List<CatalogRow> = emptyList(),
    /**
     * The add-on catalogs are still loading. Home then keeps the layout it will have once they
     * arrive (setup panel above rows), instead of a full-screen "Sign in" that turns into the
     * panel a moment later.
     */
    val catalogsLoading: Boolean = false,
    /** The hero carousel's slides (from the hero catalogs); empty: the library hero. */
    val heroSlides: List<HeroSlide> = emptyList(),
) {
    /** Nothing at all in the library: one full-screen state with the right next step. */
    val blocking: EmptyKind?
        get() = when {
            libraryCount == null || libraryCount > 0 -> null
            banner == HomeBanner.NOT_SIGNED_IN -> EmptyKind.NOT_SIGNED_IN
            banner == HomeBanner.NO_SOURCES -> EmptyKind.NO_SOURCES
            else -> EmptyKind.LIBRARY_EMPTY
        }
}

enum class HomeSection { CONTINUE_WATCHING, RECENTLY_ADDED, MOVIES, SERIES }

sealed interface HomeAction {
    data object Retry : HomeAction
    data class Tile(val action: TileAction) : HomeAction
}
