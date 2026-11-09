package dev.yourstream.ui.screens.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController

/**
 * The only thing screens use to move around. Wraps the NavController so routes stay in one
 * place and screens remain testable (they receive lambdas from their route composable).
 */
class AppNavigator(private val nav: NavHostController) {
    /**
     * Switch top-level destination: one copy each. A tab always opens at its main page (whose
     * scroll and filters are kept): sub-pages of the tab being left (a settings section, a title's
     * page) are closed first, and choosing the current tab again returns to its main page.
     */
    fun top(destination: TopLevel) {
        val stack = nav.currentBackStack.value
        val currentTab = stack.lastOrNull { e -> TopLevel.entries.any { t -> e.destination.hasRoute(t.route::class) } }
        if (currentTab != null && nav.currentBackStackEntry?.id != currentTab.id) nav.popBackStack(currentTab.destination.id, inclusive = false)
        if (currentTab != null && currentTab.destination.hasRoute(destination.route::class)) return
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun open(target: OpenTarget) = when (target) {
        is OpenTarget.Details -> nav.navigate(DetailsRoute(target.itemId))
        is OpenTarget.Episodes -> nav.navigate(EpisodesRoute(target.seriesId, target.seasonId))
        is OpenTarget.Play -> nav.navigate(PlayerRoute(target.target.itemId, target.target.episodeId, target.mediaId)) { launchSingleTop = true }
        is OpenTarget.CatalogTitle -> nav.navigate(target.route)
        is OpenTarget.Stream -> nav.navigate(target.route) { launchSingleTop = true }
        is OpenTarget.Search -> nav.navigate(SearchRoute(target.query)) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = false }
            launchSingleTop = true
        }
    }

    /** "Play next" from the player replaces the player instead of stacking another one. */
    fun replacePlayer(target: OpenTarget.Play) {
        nav.navigate(PlayerRoute(target.target.itemId, target.target.episodeId, target.mediaId)) {
            popUpTo<PlayerRoute> { inclusive = true }
        }
    }

    fun account() = nav.navigate(AccountRoute)
    fun sources() = nav.navigate(SourcesRoute)
    fun languages() = nav.navigate(LanguagesRoute)
    fun libraryStatus() = nav.navigate(LibraryStatusRoute) { launchSingleTop = true }
    fun review() = nav.navigate(MatchReviewRoute) { launchSingleTop = true }
    fun fixMatch(itemId: Long) = nav.navigate(FixMatchRoute(itemId))
    fun stats() = nav.navigate(StatsRoute) { launchSingleTop = true }
    fun tmdb() = nav.navigate(TmdbRoute) { launchSingleTop = true }
    fun addons() = nav.navigate(AddonsRoute) { launchSingleTop = true }
    fun progressSync() = nav.navigate(ProgressSyncRoute) { launchSingleTop = true }
    fun back(): Boolean = nav.popBackStack()
}
