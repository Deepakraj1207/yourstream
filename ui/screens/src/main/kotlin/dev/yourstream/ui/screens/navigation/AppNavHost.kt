package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.yourstream.ui.screens.browse.LibraryRoute as LibraryScreenRoute
import dev.yourstream.ui.screens.browse.MoviesRoute as MoviesScreenRoute
import dev.yourstream.ui.screens.browse.SeriesRoute as SeriesScreenRoute
import dev.yourstream.ui.screens.continuewatching.ContinueWatchingRoute as ContinueWatchingScreenRoute
import dev.yourstream.ui.screens.details.DetailsRoute as DetailsScreenRoute
import dev.yourstream.ui.screens.episodes.EpisodesRoute as EpisodesScreenRoute
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

// Material motion: "fade through" between tabs, "shared axis X" for opening/closing pages.
// The outgoing page fades out quickly; the incoming one fades in after it, so the two never
// visibly overlap, and only a short distance is travelled (no full-screen slide to stutter).
private const val MOTION_MS = 300
private const val OUT_MS = 90
private const val PLAYER_FADE_MS = 200
/** Reduced motion: every change is a short cross-fade; nothing slides or zooms. */
private const val REDUCED_FADE_MS = 120

private fun NavDestination.isTopLevel() = TopLevel.entries.any { hasRoute(it.route::class) }
private fun NavDestination.isPlayer() = hasRoute(PlayerRoute::class) || hasRoute(StreamPlayerRoute::class)
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabs() = initialState.destination.isTopLevel() && targetState.destination.isTopLevel()
private fun AnimatedContentTransitionScope<NavBackStackEntry>.player() = initialState.destination.isPlayer() || targetState.destination.isPlayer()

private val motion = FastOutSlowInEasing
private fun fadeInAfterOut() = fadeIn(tween(MOTION_MS - OUT_MS, delayMillis = OUT_MS, easing = LinearOutSlowInEasing))
private fun quickFadeOut() = fadeOut(tween(OUT_MS, easing = FastOutLinearInEasing))
private fun AnimatedContentTransitionScope<NavBackStackEntry>.axisOffset(full: Int) = (full * 0.08f).toInt()

/** Every page draws its own opaque background, so pages never show through each other mid-transition. */
private inline fun <reified T : Any> NavGraphBuilder.page(noinline content: @Composable (NavBackStackEntry) -> Unit) =
    composable<T> { entry -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(entry) } }

/**
 * The whole graph. Each destination wires its screen's callbacks to the [AppNavigator].
 * Tabs fade through; pages move along the horizontal axis (back reverses it); the player fades,
 * since it also leaves the navigation chrome. With reduced motion everything only cross-fades.
 */
@Composable
fun AppNavHost(nav: NavHostController, navigator: AppNavigator, appVersion: String?, modifier: Modifier = Modifier) {
    val back: () -> Unit = { navigator.back() }
    val reduced = dev.yourstream.ui.core.LocalReducedMotion.current
    NavHost(
        nav, startDestination = HomeRoute, modifier = modifier,
        enterTransition = {
            if (reduced) fadeIn(tween(REDUCED_FADE_MS)) else when {
                player() -> fadeIn(tween(PLAYER_FADE_MS))
                tabs() -> fadeInAfterOut() + scaleIn(tween(MOTION_MS - OUT_MS, delayMillis = OUT_MS, easing = motion), initialScale = 0.96f)
                else -> fadeInAfterOut() + slideInHorizontally(tween(MOTION_MS, easing = motion)) { axisOffset(it) }
            }
        },
        exitTransition = {
            if (reduced) fadeOut(tween(REDUCED_FADE_MS)) else when {
                player() -> fadeOut(tween(PLAYER_FADE_MS))
                tabs() -> quickFadeOut()
                else -> quickFadeOut() + slideOutHorizontally(tween(MOTION_MS, easing = motion)) { -axisOffset(it) }
            }
        },
        popEnterTransition = {
            if (reduced) fadeIn(tween(REDUCED_FADE_MS)) else when {
                player() -> fadeIn(tween(PLAYER_FADE_MS))
                tabs() -> fadeInAfterOut() + scaleIn(tween(MOTION_MS - OUT_MS, delayMillis = OUT_MS, easing = motion), initialScale = 0.96f)
                else -> fadeInAfterOut() + slideInHorizontally(tween(MOTION_MS, easing = motion)) { -axisOffset(it) }
            }
        },
        popExitTransition = {
            if (reduced) fadeOut(tween(REDUCED_FADE_MS)) else when {
                player() -> fadeOut(tween(PLAYER_FADE_MS))
                tabs() -> quickFadeOut()
                else -> quickFadeOut() + slideOutHorizontally(tween(MOTION_MS, easing = motion)) { axisOffset(it) }
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
                    })
                },
                onSignIn = navigator::account,
                onChooseSources = navigator::sources,
                onReview = navigator::review,
            )
        }
        page<StorageRoute> { dev.yourstream.ui.screens.settings.StorageRoute(onBack = back) }
        page<FirstRunRoute> {
            dev.yourstream.ui.screens.firstrun.FirstRunRoute(
                onConnectTelegram = navigator::account, onChooseChats = navigator::sources, onDone = navigator::finishFirstRun,
            )
        }
        page<LibraryRoute> { LibraryScreenRoute(navigator::open) }
        page<MoviesRoute> { MoviesScreenRoute(navigator::open) }
        page<SeriesRoute> { SeriesScreenRoute(navigator::open) }
        page<SearchRoute> { SearchScreenRoute(navigator::open) }
        page<ContinueWatchingRoute> { ContinueWatchingScreenRoute(navigator::open) }
        page<RecentlyAddedRoute> { RecentlyAddedScreenRoute(navigator::open) }
        val settingsLinks = SettingsLinks(navigator::libraryStatus, navigator::review, navigator::stats, navigator::progressSync, navigator::addons, navigator::tmdb, navigator::firstRun, navigator::storage)
        page<SettingsRoute> {
            SettingsScreenRoute(appVersion, navigator::account, navigator::sources, navigator::languages, settingsLinks, onPage = navigator::settingsPage)
        }
        page<SettingsPageRoute> { entry ->
            val name = entry.toRoute<SettingsPageRoute>().page
            val settingsPage = dev.yourstream.ui.screens.settings.SettingsPage.entries.firstOrNull { it.name == name } ?: dev.yourstream.ui.screens.settings.SettingsPage.APPEARANCE
            SettingsScreenRoute(appVersion, navigator::account, navigator::sources, navigator::languages, settingsLinks, page = settingsPage, onBack = back)
        }
        page<DetailsRoute> { DetailsScreenRoute(navigator::open, back, navigator::fixMatch) }
        page<EpisodesRoute> { EpisodesScreenRoute(navigator::open, back) }
        page<PlayerRoute> { PlayerScreenRoute(onBack = back, onPlay = navigator::replacePlayer) }
        page<AccountRoute> { AccountScreenRoute(back) }
        page<SourcesRoute> { SourcesScreenRoute(back) }
        page<LanguagesRoute> { LanguagesScreenRoute(back) }
        page<LibraryStatusRoute> { LibraryStatusDestination(navigator::review, navigator::sources, back, onUntitled = navigator::untitledFiles) }
        page<UntitledFilesRoute> { dev.yourstream.ui.screens.browse.UntitledRoute(navigator::open, back) }
        page<MatchReviewRoute> { MatchReviewDestination(back) }
        page<FixMatchRoute> { FixMatchDestination(back) }
        page<StatsRoute> { StatsDestination(back) }
        page<CatalogTitleRoute> { dev.yourstream.ui.screens.addon.CatalogTitleDestination(navigator::open, back) }
        page<StreamPlayerRoute> { dev.yourstream.ui.screens.addon.StreamPlayerDestination(back) }
        page<TmdbRoute> { dev.yourstream.ui.screens.settings.TmdbDestination(back) }
        page<AddonsRoute> { dev.yourstream.ui.screens.settings.AddonsDestination(back) }
        page<ProgressSyncRoute> { ProgressSyncDestination(back) }
    }
}
