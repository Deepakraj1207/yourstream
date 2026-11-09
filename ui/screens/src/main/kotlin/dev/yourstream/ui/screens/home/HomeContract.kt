package dev.yourstream.ui.screens.home

import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.TileAction
import dev.yourstream.ui.screens.navigation.OpenTarget

/** Something the user should do or know before the rows make sense. */
enum class HomeBanner { NOT_SIGNED_IN, NO_SOURCES, OFFLINE, INDEXING }

/** The featured title. [kind] decides the wording; the targets come from the domain's entries. */
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

enum class HomeSection { CONTINUE_WATCHING, RECENTLY_ADDED, MOVIES, SERIES, FAVORITES }

sealed interface HomeAction {
    data object Retry : HomeAction
    data class Tile(val action: TileAction) : HomeAction
}
