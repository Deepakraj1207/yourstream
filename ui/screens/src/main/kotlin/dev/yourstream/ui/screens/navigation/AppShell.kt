package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
    val reducedMotion = settings.reduceMotion || dev.yourstream.ui.core.rememberSystemReducedMotion()
    androidx.compose.runtime.CompositionLocalProvider(
        dev.yourstream.ui.screens.settings.LocalAppSettings provides settings,
        dev.yourstream.ui.core.LocalReducedMotion provides reducedMotion,
    ) {
    YourStreamTheme(
        layout, darkTheme = dark, manga = settings.style == dev.yourstream.ui.screens.settings.UiStyle.MANGA,
        amoled = settings.amoledBlack, posterScale = settings.posterSize.scale,
    ) {
        Surface(Modifier.fillMaxSize()) {
            val nav = rememberNavController()
            val navigator = remember(nav) { AppNavigator(nav) }
            val entry by nav.currentBackStackEntryAsState()
            val destination = entry?.destination
            val current = TopLevel.entries.firstOrNull { t -> destination?.hierarchy?.any { it.hasRoute(t.route::class) } == true }
            // The players and the first-run guide use the whole screen, without navigation.
            val fullScreen = destination?.hasRoute(PlayerRoute::class) == true || destination?.hasRoute(StreamPlayerRoute::class) == true ||
                destination?.hasRoute(FirstRunRoute::class) == true
            val graph = remember(nav) { movableContentOf { m: Modifier -> AppNavHost(nav, navigator, appVersion, m) } }
            val shell: ShellViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
            val indexing by shell.indexing.collectAsStateWithLifecycle()
            // First launch: the guided first run opens over Home (finishing or skipping it returns there).
            val openFirstRun by shell.openFirstRun.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(openFirstRun) {
                if (openFirstRun) { shell.firstRunOpened(); navigator.firstRun() }
            }
            if (fullScreen) graph(Modifier.fillMaxSize())
            else AdaptiveShell(layout, current, navigator::top, indexing, navigator::libraryStatus) { graph(it) }
            // A newer version on the Releases page: offered once per session (not over the player).
            if (!fullScreen && !androidx.compose.ui.platform.LocalInspectionMode.current) dev.yourstream.ui.screens.update.UpdatePrompt()
        }
    }
    }
}

/**
 * Navigation chrome per layout, all with the same [MAIN_TABS]: bottom navigation bar (phone),
 * rail (tablet), drawer (large), TV drawer.
 * While indexing runs, its status floats at the bottom of every tab ([IndexingPill]).
 */
@Composable
fun AdaptiveShell(
    layout: WindowLayout,
    current: TopLevel?,
    onSelect: (TopLevel) -> Unit,
    indexing: dev.yourstream.ui.screens.IndexingStatus = dev.yourstream.ui.screens.IndexingStatus(),
    onIndexing: () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val withStatus: @Composable (Modifier) -> Unit = { m ->
        androidx.compose.foundation.layout.Box(m) {
            content(Modifier.fillMaxSize())
            IndexingPill(indexing, onIndexing, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 8.dp))
        }
    }
    when (layout) {
        WindowLayout.COMPACT -> {
            // A Material 3 navigation bar under the content on the main tabs; other pages (details,
            // players, settings pages) use the whole screen. Nothing floats over the lists, so they
            // need no extra bottom padding (LocalBottomClearance stays 0).
            val showBar = current != null
            Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                // Status bar padding applied (and consumed) here, as the old scaffold did: Home has no
                // top bar of its own, and screens' top bars must not add it a second time. The
                // navigation bar takes the system navigation bar's inset when it is shown.
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
                        .let { if (showBar) it.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.navigationBars) else it.navigationBarsPadding() },
                ) {
                    content(Modifier.fillMaxSize())
                    IndexingPill(indexing, onIndexing, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 8.dp))
                }
                if (showBar) MainNavigationBar(current, onSelect)
            }
        }

        WindowLayout.MEDIUM -> Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.testTag("nav:rail")) {
                MAIN_TABS.forEach { t ->
                    NavigationRailItem(
                        selected = t == current?.tab, onClick = { onSelect(t) },
                        icon = { Icon(t.icon, contentDescription = null) }, label = { Text(stringResource(t.label)) },
                        modifier = Modifier.focusHighlight().testTag("nav:${t.name}"),
                    )
                }
            }
            withStatus(Modifier.weight(1f))
        }

        WindowLayout.EXPANDED -> PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(240.dp).testTag("nav:drawer")) {
                    MAIN_TABS.forEach { t ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(t.label)) }, selected = t == current?.tab, onClick = { onSelect(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            modifier = Modifier.padding(horizontal = 12.dp).focusHighlight().testTag("nav:${t.name}"),
                        )
                    }
                }
            },
        ) { withStatus(Modifier.fillMaxSize()) }

        WindowLayout.TV -> TvShell(current, onSelect, withStatus)
    }
}

/** Compose for TV: a collapsible side drawer that opens when focus moves into it (D-pad left). */
@Composable
private fun TvShell(current: TopLevel?, onSelect: (TopLevel) -> Unit, content: @Composable (Modifier) -> Unit) {
    androidx.tv.material3.NavigationDrawer(
        drawerContent = {
            Column(Modifier.padding(12.dp).testTag("nav:tv")) {
                MAIN_TABS.forEach { t ->
                    TvNavigationDrawerItem(
                        selected = t == current?.tab, onClick = { onSelect(t) },
                        leadingContent = { androidx.tv.material3.Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.testTag("nav:${t.name}"),
                    ) { androidx.tv.material3.Text(stringResource(t.label)) }
                }
            }
        },
    ) { content(Modifier.fillMaxSize()) }
}

/**
 * The phone's navigation: a Material 3 navigation bar with the six main tabs. Each item is a
 * full-height tab ("tab" role, selected state for screen readers). Tab names show under every
 * icon, or only under the selected one (Settings → Appearance → Tab names); the names stay in the
 * accessibility tree either way. Manga style adds an ink rule along the top.
 *
 * Built from M3's own container colours, elevation, insets and [NavigationBarItem]s, but without
 * the 8 dp gaps `NavigationBar` puts between items: with six tabs those gaps leave each tab under
 * 48 dp wide on 320 dp screens (small phones, or large display-size settings). Without them every
 * tab is at least 53 dp wide there.
 */
@Composable
fun MainNavigationBar(current: TopLevel?, onSelect: (TopLevel) -> Unit, modifier: Modifier = Modifier) {
    val labels = dev.yourstream.ui.screens.settings.LocalAppSettings.current.dockLabels
    val manga = dev.yourstream.ui.core.LocalMangaStyle.current
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    Surface(
        color = androidx.compose.material3.NavigationBarDefaults.containerColor,
        tonalElevation = androidx.compose.material3.NavigationBarDefaults.Elevation,
        modifier = modifier
            .then(if (manga) Modifier.drawWithContent { drawContent(); drawRect(ink, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) } else Modifier)
            .testTag("nav:bar"),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(androidx.compose.material3.NavigationBarDefaults.windowInsets)
                .height(80.dp)
                .selectableGroup(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            MAIN_TABS.forEach { t ->
                NavigationBarItem(
                    selected = t == current?.tab, onClick = { onSelect(t) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(stringResource(t.label), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    alwaysShowLabel = labels,
                    modifier = Modifier.focusHighlight().testTag("nav:${t.name}"),
                )
            }
        }
    }
}
