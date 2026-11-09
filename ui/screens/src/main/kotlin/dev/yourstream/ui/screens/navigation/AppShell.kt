package dev.yourstream.ui.screens.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.NavigationDrawerItem as TvNavigationDrawerItem
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.rememberWindowLayout

/**
 * The app's root composable: theme, navigation, and the shell for the current [WindowLayout].
 * The graph is movable content, so switching shells (e.g. entering the full-screen player)
 * never recreates screens.
 */
@Composable
fun YourStreamUi(onDeveloperTools: (() -> Unit)? = null, appVersion: String? = null, layout: WindowLayout = rememberWindowLayout()) {
    YourStreamTheme(layout) {
        Surface(Modifier.fillMaxSize()) {
            val nav = rememberNavController()
            val navigator = remember(nav) { AppNavigator(nav) }
            val entry by nav.currentBackStackEntryAsState()
            val destination = entry?.destination
            val current = TopLevel.entries.firstOrNull { t -> destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true }
            val fullScreen = destination?.hasRoute(PlayerRoute::class) == true
            val graph = remember(nav) { movableContentOf { m: Modifier -> AppNavHost(nav, navigator, onDeveloperTools, appVersion, m) } }
            if (fullScreen) graph(Modifier.fillMaxSize())
            else AdaptiveShell(layout, current, navigator::top) { graph(it) }
        }
    }
}

/** Navigation chrome per layout: bottom bar (phone), rail (tablet), drawer (large), TV drawer. */
@Composable
fun AdaptiveShell(layout: WindowLayout, current: TopLevel?, onSelect: (TopLevel) -> Unit, content: @Composable (Modifier) -> Unit) {
    when (layout) {
        WindowLayout.COMPACT -> Scaffold(
            bottomBar = {
                NavigationBar(Modifier.testTag("nav:bar")) {
                    TopLevel.entries.filter { it.compact }.forEach { t ->
                        NavigationBarItem(
                            selected = t == current, onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) }, label = { Text(stringResource(t.label)) },
                            modifier = Modifier.testTag("nav:${t.name}"),
                        )
                    }
                }
            },
        ) { padding -> content(Modifier.padding(padding)) }

        WindowLayout.MEDIUM -> Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.testTag("nav:rail")) {
                TopLevel.entries.forEach { t ->
                    NavigationRailItem(
                        selected = t == current, onClick = { onSelect(t) },
                        icon = { Icon(t.icon, contentDescription = null) }, label = { Text(stringResource(t.label)) },
                        modifier = Modifier.focusHighlight().testTag("nav:${t.name}"),
                    )
                }
            }
            content(Modifier.weight(1f))
        }

        WindowLayout.EXPANDED -> PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(240.dp).testTag("nav:drawer")) {
                    TopLevel.entries.forEach { t ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(t.label)) }, selected = t == current, onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            modifier = Modifier.padding(horizontal = 12.dp).focusHighlight().testTag("nav:${t.name}"),
                        )
                    }
                }
            },
        ) { content(Modifier.fillMaxSize()) }

        WindowLayout.TV -> TvShell(current, onSelect, content)
    }
}

/** Compose for TV: a collapsible side drawer that opens when focus moves into it (D-pad left). */
@Composable
private fun TvShell(current: TopLevel?, onSelect: (TopLevel) -> Unit, content: @Composable (Modifier) -> Unit) {
    androidx.tv.material3.NavigationDrawer(
        drawerContent = {
            Column(Modifier.padding(12.dp).testTag("nav:tv")) {
                TopLevel.entries.forEach { t ->
                    TvNavigationDrawerItem(
                        selected = t == current, onClick = { onSelect(t) },
                        leadingContent = { androidx.tv.material3.Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.testTag("nav:${t.name}"),
                    ) { androidx.tv.material3.Text(stringResource(t.label)) }
                }
            }
        },
    ) { content(Modifier.fillMaxSize()) }
}
