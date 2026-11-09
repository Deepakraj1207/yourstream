package dev.yourstream.ui.screens.home

import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.screens.Tile

/** Something the user should do or know before the rows make sense. */
enum class HomeBanner { NOT_SIGNED_IN, NO_SOURCES, OFFLINE, INDEXING }

data class HomeUiState(
    val banner: HomeBanner? = null,
    val continueWatching: UiState<List<Tile>> = UiState.Loading,
    val recentlyAdded: UiState<List<Tile>> = UiState.Loading,
    val favorites: UiState<List<Tile>> = UiState.Loading,
) {
    /** Nothing at all to show: the banner's empty state takes the whole screen. */
    val blocking: EmptyKind?
        get() = when {
            listOf(continueWatching, recentlyAdded, favorites).any { it !is UiState.Empty } -> null
            banner == HomeBanner.NOT_SIGNED_IN -> EmptyKind.NOT_SIGNED_IN
            banner == HomeBanner.NO_SOURCES -> EmptyKind.NO_SOURCES
            else -> EmptyKind.LIBRARY_EMPTY
        }
}

enum class HomeSection { CONTINUE_WATCHING, RECENTLY_ADDED, FAVORITES }

sealed interface HomeAction {
    data object Retry : HomeAction
}
