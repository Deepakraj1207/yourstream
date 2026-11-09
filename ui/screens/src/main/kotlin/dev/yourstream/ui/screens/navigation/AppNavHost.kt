package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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

private const val NAV_FADE_MS = 120

/** The whole graph. Each destination wires its screen's callbacks to the [AppNavigator]. */
@Composable
fun AppNavHost(nav: NavHostController, navigator: AppNavigator, appVersion: String?, modifier: Modifier = Modifier) {
    val back: () -> Unit = { navigator.back() }
    // Screens have transparent backgrounds, so a cross-fade draws both on top of each other
    // (ghosted text, flicker). The old screen leaves at once; the new one fades in quickly.
    NavHost(
        nav, startDestination = HomeRoute, modifier = modifier,
        enterTransition = { fadeIn(tween(NAV_FADE_MS)) },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { fadeIn(tween(NAV_FADE_MS)) },
        popExitTransition = { ExitTransition.None },
    ) {
        composable<HomeRoute> {
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
        composable<LibraryRoute> { LibraryScreenRoute(navigator::open) }
        composable<MoviesRoute> { MoviesScreenRoute(navigator::open) }
        composable<SeriesRoute> { SeriesScreenRoute(navigator::open) }
        composable<SearchRoute> { SearchScreenRoute(navigator::open) }
        composable<ContinueWatchingRoute> { ContinueWatchingScreenRoute(navigator::open) }
        composable<RecentlyAddedRoute> { RecentlyAddedScreenRoute(navigator::open) }
        composable<FavoritesRoute> { FavoritesScreenRoute(navigator::open) }
        composable<SettingsRoute> {
            SettingsScreenRoute(
                appVersion, navigator::account, navigator::sources, navigator::languages,
                SettingsLinks(navigator::libraryStatus, navigator::review, navigator::stats, navigator::progressSync, navigator::addons),
            )
        }
        composable<DetailsRoute> { DetailsScreenRoute(navigator::open, back, navigator::fixMatch) }
        composable<EpisodesRoute> { EpisodesScreenRoute(navigator::open, back) }
        composable<PlayerRoute> { PlayerScreenRoute(onBack = back, onPlay = navigator::replacePlayer) }
        composable<AccountRoute> { AccountScreenRoute(back) }
        composable<SourcesRoute> { SourcesScreenRoute(back) }
        composable<LanguagesRoute> { LanguagesScreenRoute(back) }
        composable<LibraryStatusRoute> { LibraryStatusDestination(navigator::review, navigator::sources, back) }
        composable<MatchReviewRoute> { MatchReviewDestination(back) }
        composable<FixMatchRoute> { FixMatchDestination(back) }
        composable<StatsRoute> { StatsDestination(back) }
        composable<AddonsRoute> { dev.yourstream.ui.screens.settings.AddonsDestination(back) }
        composable<ProgressSyncRoute> { ProgressSyncDestination(back) }
    }
}
