package dev.yourstream.ui.screens.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import dev.yourstream.ui.core.AppIcons
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
/** [query]: start with this text (a catalog title to look for in the library). */
@Serializable data class SearchRoute(val query: String = "") : Route
@Serializable data object ContinueWatchingRoute : Route
@Serializable data object RecentlyAddedRoute : Route
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
/** The automatic library: sync and matching progress, stage by stage. */
@Serializable data object LibraryStatusRoute : Route
/** Titles the matcher was unsure about. */
@Serializable data object MatchReviewRoute : Route
/** Choose the right metadata for one title (from Details). */
@Serializable data class FixMatchRoute(val itemId: Long) : Route
@Serializable data object StatsRoute : Route
/** A title from an add-on catalog: details, episodes and the add-ons' streams. */
@Serializable data class CatalogTitleRoute(
    val provider: String, val type: String, val id: String, val title: String,
    val year: Int? = null, val poster: String? = null, val imdb: String? = null,
) : Route
/** Plays an add-on stream (HTTP file, HLS or DASH). [headers]: JSON object of request headers. */
@Serializable data class StreamPlayerRoute(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val headers: String = "{}",
    /** Torrent streams ([url] is then empty): the info hash, the file to play (-1: largest video), extra trackers (one per line). */
    val infoHash: String? = null,
    val fileIdx: Int = -1,
    val sources: String = "",
    val fileName: String? = null,
    /** Where to remember the position (catalog title + episode, `StreamProgressStore.keyOf`); null: not remembered. */
    val progressKey: String? = null,
) : Route
/** A Settings category page ([page]: a SettingsPage name). */
@Serializable data class SettingsPageRoute(val page: String) : Route
/** Library status → files without a title (hidden from browsing). */
@Serializable data object UntitledFilesRoute : Route
/** Settings → TMDB key. */
@Serializable data object TmdbRoute : Route
/** Catalog add-ons: built-in ones and those added by link. */
@Serializable data object AddonsRoute : Route
/** Optional multi-device progress sync. */
@Serializable data object ProgressSyncRoute : Route
/** Settings → Content → Files on this device. */
@Serializable data object LocalFilesRoute : Route
/** Settings → Advanced → Storage. */
@Serializable data object StorageRoute : Route
/** The guided first run: welcome, Telegram, chats, indexing, library, playback basics. */
@Serializable data object FirstRunRoute : Route

/**
 * Top-level destinations: pages that open without a back arrow and keep their state when another
 * tab is chosen. [MAIN_TABS] are the ones in the navigation (bar, rail, drawer, TV drawer); the
 * others open from Home's "See all" or the Library and count as Home ([tab]).
 */
enum class TopLevel(val route: Route, @StringRes val label: Int, val icon: ImageVector) {
    HOME(HomeRoute, R.string.nav_home, Icons.Filled.Home),
    LIBRARY(LibraryRoute, R.string.nav_library, Icons.AutoMirrored.Filled.List),
    MOVIES(MoviesRoute, R.string.nav_movies, AppIcons.Movie),
    SERIES(SeriesRoute, R.string.nav_series, AppIcons.Tv),
    SEARCH(SearchRoute(), R.string.nav_search, Icons.Filled.Search),
    CONTINUE(ContinueWatchingRoute, R.string.nav_continue, Icons.Filled.PlayArrow),
    RECENT(RecentlyAddedRoute, R.string.nav_recent, Icons.Filled.DateRange),
    SETTINGS(SettingsRoute, R.string.nav_settings, Icons.Filled.Settings);

    /** The navigation item shown as selected on this destination. */
    val tab: TopLevel get() = when (this) {
        // Opened from Home's rows ("See all") and the Library's filters, not from the navigation.
        CONTINUE, RECENT, MOVIES, SERIES -> HOME
        else -> this
    }
}

/**
 * The navigation's items, in order, on every layout (phone bar, tablet rail, drawer, TV). Movies
 * and Series are not tabs (user's choice, 13 Nov 2026): Home's rows and the Library's filters
 * open them.
 */
val MAIN_TABS: List<TopLevel> = listOf(TopLevel.HOME, TopLevel.SEARCH, TopLevel.LIBRARY, TopLevel.SETTINGS)

/** What tapping a card opens. Decided by the ViewModel's mapping, executed by the navigator. */
sealed interface OpenTarget {
    data class Details(val itemId: Long) : OpenTarget
    data class Episodes(val seriesId: Long, val seasonId: Long? = null) : OpenTarget
    data class Play(val target: PlaybackTarget, val mediaId: Long? = null) : OpenTarget
    /** Look for a title in the library. */
    data class Search(val query: String) : OpenTarget
    /** An add-on catalog title (not in the library). */
    data class CatalogTitle(val route: CatalogTitleRoute) : OpenTarget
    /** An add-on stream. */
    data class Stream(val route: StreamPlayerRoute) : OpenTarget
}
