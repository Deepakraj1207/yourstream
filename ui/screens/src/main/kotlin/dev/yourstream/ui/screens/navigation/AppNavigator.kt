package dev.yourstream.ui.screens.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/**
 * The only thing screens use to move around. Wraps the NavController so routes stay in one
 * place and screens remain testable (they receive lambdas from their route composable).
 */
class AppNavigator(private val nav: NavHostController) {
    /** Switch top-level destination: one copy each, scroll/filters kept (save/restore state). */
    fun top(destination: TopLevel) {
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
    fun back(): Boolean = nav.popBackStack()
}
