package dev.yourstream.ui.screens.navigation

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
import dev.yourstream.ui.screens.search.SearchRoute as SearchScreenRoute
import dev.yourstream.ui.screens.settings.AccountRoute as AccountScreenRoute
import dev.yourstream.ui.screens.settings.LanguagesRoute as LanguagesScreenRoute
import dev.yourstream.ui.screens.settings.SettingsRoute as SettingsScreenRoute
import dev.yourstream.ui.screens.settings.SourcesRoute as SourcesScreenRoute

/** The whole graph. Each destination wires its screen's callbacks to the [AppNavigator]. */
@Composable
fun AppNavHost(nav: NavHostController, navigator: AppNavigator, onDeveloperTools: (() -> Unit)?, appVersion: String?, modifier: Modifier = Modifier) {
    val back: () -> Unit = { navigator.back() }
    NavHost(nav, startDestination = HomeRoute, modifier = modifier) {
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
            )
        }
        composable<LibraryRoute> { LibraryScreenRoute(navigator::open) }
        composable<MoviesRoute> { MoviesScreenRoute(navigator::open) }
        composable<SeriesRoute> { SeriesScreenRoute(navigator::open) }
        composable<SearchRoute> { SearchScreenRoute(navigator::open) }
        composable<ContinueWatchingRoute> { ContinueWatchingScreenRoute(navigator::open) }
        composable<RecentlyAddedRoute> { RecentlyAddedScreenRoute(navigator::open) }
        composable<FavoritesRoute> { FavoritesScreenRoute(navigator::open) }
        composable<SettingsRoute> { SettingsScreenRoute(appVersion, navigator::account, navigator::sources, navigator::languages, onDeveloperTools) }
        composable<DetailsRoute> { DetailsScreenRoute(navigator::open, back) }
        composable<EpisodesRoute> { EpisodesScreenRoute(navigator::open, back) }
        composable<PlayerRoute> { PlayerScreenRoute(onBack = back, onPlayNext = navigator::replacePlayer) }
        composable<AccountRoute> { AccountScreenRoute(back) }
        composable<SourcesRoute> { SourcesScreenRoute(back) }
        composable<LanguagesRoute> { LanguagesScreenRoute(back) }
    }
}
