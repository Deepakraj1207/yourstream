package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import dev.yourstream.ui.core.inkPanel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun YourStreamUi(appVersion: String? = null, layout: WindowLayout = rememberWindowLayout()) {
    val prefs: dev.yourstream.ui.screens.settings.AppPreferencesViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val dark = when (settings.theme) {
        dev.yourstream.ui.screens.settings.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        dev.yourstream.ui.screens.settings.ThemeMode.DARK -> true
        dev.yourstream.ui.screens.settings.ThemeMode.LIGHT -> false
    }
    androidx.compose.runtime.CompositionLocalProvider(dev.yourstream.ui.screens.settings.LocalAppSettings provides settings) {
    YourStreamTheme(layout, darkTheme = dark, manga = settings.style == dev.yourstream.ui.screens.settings.UiStyle.MANGA) {
        Surface(Modifier.fillMaxSize()) {
            val nav = rememberNavController()
            val navigator = remember(nav) { AppNavigator(nav) }
            val entry by nav.currentBackStackEntryAsState()
            val destination = entry?.destination
            val current = TopLevel.entries.firstOrNull { t -> destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true }
            val fullScreen = destination?.hasRoute(PlayerRoute::class) == true || destination?.hasRoute(StreamPlayerRoute::class) == true
            val graph = remember(nav) { movableContentOf { m: Modifier -> AppNavHost(nav, navigator, appVersion, m) } }
            if (fullScreen) graph(Modifier.fillMaxSize())
            else AdaptiveShell(layout, current, navigator::top) { graph(it) }
            // A newer version on the Releases page: offered once per session (not over the player).
            if (!fullScreen && !androidx.compose.ui.platform.LocalInspectionMode.current) dev.yourstream.ui.screens.update.UpdatePrompt()
        }
    }
    }
}

/** Navigation chrome per layout: bottom bar (phone), rail (tablet), drawer (large), TV drawer. */
@Composable
fun AdaptiveShell(layout: WindowLayout, current: TopLevel?, onSelect: (TopLevel) -> Unit, content: @Composable (Modifier) -> Unit) {
    when (layout) {
        WindowLayout.COMPACT -> Scaffold(
            bottomBar = {
                if (dev.yourstream.ui.core.LocalMangaStyle.current) MangaNavBar(current, onSelect)
                else NavigationBar(Modifier.testTag("nav:bar")) {
                    TopLevel.entries.filter { it.compact }.forEach { t ->
                        NavigationBarItem(
                            selected = t == current, onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) }, label = { Text(stringResource(t.label)) },
                            modifier = Modifier.testTag("nav:${t.name}"),
                        )
                    }
                }
            },
            // Consumed here so screens' top bars do not add the status bar a second time.
        ) { padding -> content(Modifier.padding(padding).consumeWindowInsets(padding)) }

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

/**
 * The phone's bottom bar in the manga style: a floating capsule with an ink outline and a hard
 * shadow. Tabs are icons; the current one grows into an ink pill that shows its name in comic
 * lettering (spring animation).
 */
@Composable
private fun MangaNavBar(current: TopLevel?, onSelect: (TopLevel) -> Unit) {
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val paper = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer
    val capsule = androidx.compose.foundation.shape.CircleShape
    val springy = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.7f, stiffness = 500f)
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(end = 4.dp, bottom = 4.dp)
                .inkPanel(capsule, shadow = 4.dp, border = 2.5.dp)
                .clip(capsule).background(paper).padding(horizontal = 8.dp).testTag("nav:bar"),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TopLevel.entries.filter { it.compact }.forEach { t ->
                val selected = t == current
                val fill by androidx.compose.animation.animateColorAsState(if (selected) ink else androidx.compose.ui.graphics.Color.Transparent, label = "tab-fill")
                val tint by androidx.compose.animation.animateColorAsState(if (selected) paper else ink, label = "tab-tint")
                val label = stringResource(t.label)
                Row(
                    Modifier.height(44.dp).clip(capsule).background(fill)
                        .selectable(selected = selected, onClick = { onSelect(t) }, role = androidx.compose.ui.semantics.Role.Tab)
                        .focusHighlight(capsule)
                        .semantics { contentDescription = label }
                        .padding(horizontal = if (selected) 16.dp else 14.dp)
                        .animateContentSize(springy)
                        .testTag("nav:${t.name}"),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    if (selected) Text(
                        label.uppercase(), fontFamily = dev.yourstream.ui.core.Manga.Lettering, fontSize = 16.sp, letterSpacing = 1.sp,
                        maxLines = 1, color = tint, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
