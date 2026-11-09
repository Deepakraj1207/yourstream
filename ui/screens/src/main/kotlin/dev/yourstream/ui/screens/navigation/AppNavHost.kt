package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.yourstream.ui.screens.browse.LibraryRoute as LibraryScreenRoute
import dev.yourstream.ui.screens.browse.MoviesRoute as MoviesScreenRoute
import dev.yourstream.ui.screens.browse.SeriesRoute as SeriesScreenRoute
import dev.yourstream.ui.screens.continuewatching.ContinueWatchingRoute as ContinueWatchingScreenRoute
import dev.yourstream.ui.screens.details.DetailsRoute as DetailsScreenRoute
import dev.yourstream.ui.screens.episodes.EpisodesRoute as EpisodesScreenRoute
import dev.yourstream.ui.screens.browse.FavoritesRoute as FavoritesScreenRoute
import dev.yourstream.ui.screens.browse.RecentlyAddedRoute as RecentlyAddedScreenRoute
import dev.yourstream.ui.screens.home.HomeRoute as HomeScreenRoute
import dev.yourstream.ui.screens.home.HomeSection
import dev.yourstream.ui.screens.player.PlayerRoute as PlayerScreenRoute
import dev.yourstream.ui.screens.library.FixMatchDestination
import dev.yourstream.ui.screens.library.LibraryStatusDestination
import dev.yourstream.ui.screens.library.MatchReviewDestination
import dev.yourstream.ui.screens.settings.SettingsLinks
import dev.yourstream.ui.screens.stats.StatsDestination
import dev.yourstream.ui.screens.sync.ProgressSyncDestination
import dev.yourstream.ui.screens.search.SearchRoute as SearchScreenRoute
import dev.yourstream.ui.screens.settings.AccountRoute as AccountScreenRoute
import dev.yourstream.ui.screens.settings.LanguagesRoute as LanguagesScreenRoute
import dev.yourstream.ui.screens.settings.SettingsRoute as SettingsScreenRoute
import dev.yourstream.ui.screens.settings.SourcesRoute as SourcesScreenRoute

private const val NAV_SLIDE_MS = 280
private const val NAV_FADE_MS = 150

private fun NavDestination.isTopLevel() = TopLevel.entries.any { hasRoute(it.route::class) }
private fun NavDestination.isPlayer() = hasRoute(PlayerRoute::class)
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabs() = initialState.destination.isTopLevel() && targetState.destination.isTopLevel()
private fun AnimatedContentTransitionScope<NavBackStackEntry>.player() = initialState.destination.isPlayer() || targetState.destination.isPlayer()
private val slide = tween<IntOffset>(NAV_SLIDE_MS, easing = FastOutSlowInEasing)

/** Every page draws its own opaque background, so pages never show through each other mid-transition. */
private inline fun <reified T : Any> NavGraphBuilder.page(noinline content: @Composable (NavBackStackEntry) -> Unit) =
    composable<T> { entry -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(entry) } }

/**
 * The whole graph. Each destination wires its screen's callbacks to the [AppNavigator].
 * Tabs switch instantly (like any bottom-bar app); opening a page slides it in from the right over
 * the current one, which moves a little to the left; back is the reverse. The player fades, since
 * it also leaves the navigation chrome.
 */
@Composable
fun AppNavHost(nav: NavHostController, navigator: AppNavigator, appVersion: String?, modifier: Modifier = Modifier) {
    val back: () -> Unit = { navigator.back() }
    NavHost(
        nav, startDestination = HomeRoute, modifier = modifier,
        enterTransition = {
            when {
                tabs() -> EnterTransition.None
                player() -> fadeIn(tween(NAV_FADE_MS))
                else -> slideInHorizontally(slide) { it }
            }
        },
        exitTransition = {
            when {
                tabs() -> ExitTransition.None
                player() -> fadeOut(tween(NAV_FADE_MS))
                else -> slideOutHorizontally(slide) { -it / 4 }
            }
        },
        popEnterTransition = {
            when {
                tabs() -> EnterTransition.None
                player() -> fadeIn(tween(NAV_FADE_MS))
                else -> slideInHorizontally(slide) { -it / 4 }
            }
        },
        popExitTransition = {
            when {
                tabs() -> ExitTransition.None
                player() -> fadeOut(tween(NAV_FADE_MS))
                else -> slideOutHorizontally(slide) { it }
            }
        },
    ) {
        page<HomeRoute> {
            HomeScreenRoute(
                onOpen = navigator::open,
                onSeeAll = { s ->
                    navigator.top(when (s) {
                        HomeSection.CONTINUE_WATCHING -> TopLevel.CONTINUE
                        HomeSection.RECENTLY_ADDED -> TopLevel.RECENT
                        HomeSection.MOVIES -> TopLevel.MOVIES
                        HomeSection.SERIES -> TopLevel.SERIES
                        HomeSection.FAVORITES -> TopLevel.FAVORITES
                    })
                },
                onSignIn = navigator::account,
                onChooseSources = navigator::sources,
                onReview = navigator::review,
            )
        }
        page<LibraryRoute> { LibraryScreenRoute(navigator::open) }
        page<MoviesRoute> { MoviesScreenRoute(navigator::open) }
        page<SeriesRoute> { SeriesScreenRoute(navigator::open) }
        page<SearchRoute> { SearchScreenRoute(navigator::open) }
        page<ContinueWatchingRoute> { ContinueWatchingScreenRoute(navigator::open) }
        page<RecentlyAddedRoute> { RecentlyAddedScreenRoute(navigator::open) }
        page<FavoritesRoute> { FavoritesScreenRoute(navigator::open) }
        page<SettingsRoute> {
            SettingsScreenRoute(
                appVersion, navigator::account, navigator::sources, navigator::languages,
                SettingsLinks(navigator::libraryStatus, navigator::review, navigator::stats, navigator::progressSync, navigator::addons),
            )
        }
        page<DetailsRoute> { DetailsScreenRoute(navigator::open, back, navigator::fixMatch) }
        page<EpisodesRoute> { EpisodesScreenRoute(navigator::open, back) }
        page<PlayerRoute> { PlayerScreenRoute(onBack = back, onPlay = navigator::replacePlayer) }
        page<AccountRoute> { AccountScreenRoute(back) }
        page<SourcesRoute> { SourcesScreenRoute(back) }
        page<LanguagesRoute> { LanguagesScreenRoute(back) }
        page<LibraryStatusRoute> { LibraryStatusDestination(navigator::review, navigator::sources, back) }
        page<MatchReviewRoute> { MatchReviewDestination(back) }
        page<FixMatchRoute> { FixMatchDestination(back) }
        page<StatsRoute> { StatsDestination(back) }
        page<AddonsRoute> { dev.yourstream.ui.screens.settings.AddonsDestination(back) }
        page<ProgressSyncRoute> { ProgressSyncDestination(back) }
    }
}
