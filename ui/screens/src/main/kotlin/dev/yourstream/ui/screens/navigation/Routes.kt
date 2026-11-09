package dev.yourstream.ui.screens.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.graphics.vector.ImageVector
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.ui.screens.R
import kotlinx.serialization.Serializable

/** Type-safe destinations (Navigation Compose serializes them into the back stack). */
@Serializable sealed interface Route

@Serializable data object HomeRoute : Route
@Serializable data object LibraryRoute : Route
@Serializable data object MoviesRoute : Route
@Serializable data object SeriesRoute : Route
@Serializable data object SearchRoute : Route
@Serializable data object ContinueWatchingRoute : Route
@Serializable data object FavoritesRoute : Route
@Serializable data object SettingsRoute : Route

/** A movie, video or series. */
@Serializable data class DetailsRoute(val itemId: Long) : Route
/** Episodes of a series, optionally opened on one season. */
@Serializable data class EpisodesRoute(val seriesId: Long, val seasonId: Long? = null) : Route
@Serializable data class PlayerRoute(val itemId: Long, val episodeId: Long? = null, val mediaId: Long? = null) : Route {
    val target: PlaybackTarget get() = PlaybackTarget(itemId, episodeId)
}
@Serializable data object AccountRoute : Route
@Serializable data object SourcesRoute : Route
@Serializable data object LanguagesRoute : Route

/**
 * Top-level destinations. [compact] = shown in the phone's bottom bar (max 5); all appear in the
 * rail, drawer and TV drawer. Movies, Series, Continue Watching on phones: from Library / Home.
 */
enum class TopLevel(val route: Route, @StringRes val label: Int, val icon: ImageVector, val compact: Boolean) {
    HOME(HomeRoute, R.string.nav_home, Icons.Filled.Home, true),
    LIBRARY(LibraryRoute, R.string.nav_library, Icons.AutoMirrored.Filled.List, true),
    MOVIES(MoviesRoute, R.string.nav_movies, Icons.Filled.Star, false),
    SERIES(SeriesRoute, R.string.nav_series, Icons.Filled.ThumbUp, false),
    SEARCH(SearchRoute, R.string.nav_search, Icons.Filled.Search, true),
    CONTINUE(ContinueWatchingRoute, R.string.nav_continue, Icons.Filled.PlayArrow, false),
    FAVORITES(FavoritesRoute, R.string.nav_favorites, Icons.Filled.Favorite, true),
    SETTINGS(SettingsRoute, R.string.nav_settings, Icons.Filled.Settings, true),
}

/** What tapping a card opens. Decided by the ViewModel's mapping, executed by the navigator. */
sealed interface OpenTarget {
    data class Details(val itemId: Long) : OpenTarget
    data class Episodes(val seriesId: Long, val seasonId: Long? = null) : OpenTarget
    data class Play(val target: PlaybackTarget, val mediaId: Long? = null) : OpenTarget
}
