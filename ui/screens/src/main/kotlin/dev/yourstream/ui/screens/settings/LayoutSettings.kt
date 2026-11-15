package dev.yourstream.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.ui.core.AppIcons
import dev.yourstream.ui.core.CardLook
import dev.yourstream.ui.core.LocalCardLook
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.core.PosterCard
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.ScreensModule
import dev.yourstream.ui.screens.home.HomeCatalogLayout
import dev.yourstream.ui.screens.home.HomeCatalogPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → Layout, in Nuvio's structure: a hub (Home: Home layout, Continue Watching, Poster
 * card style; Streams; Detail page; Navigation bar), each opening its own page. Every option
 * changes those screens.
 */
internal fun layoutEntries(): List<SettingEntry> = buildList {
    // ---- The hub
    val la = SettingsPage.LAYOUT
    fun link(key: String, page: SettingsPage, @StringRes group: Int, keywords: String) = entry(key, la, group, page.title, keywords) {
        SettingRow(stringResource(page.title), pageSummary(page, host), page.icon, tag = "settings-page:${page.name}") { host.onPage(page) }
    }
    add(link("layout-home", SettingsPage.LAYOUT_HOME, R.string.layout_group_home, "home rows hero catalogs"))
    add(link("layout-continue", SettingsPage.LAYOUT_CONTINUE, R.string.layout_group_home, "continue watching resume"))
    add(link("layout-posters", SettingsPage.LAYOUT_POSTERS, R.string.layout_group_home, "poster card width corner radius labels"))
    add(link("layout-streams", SettingsPage.LAYOUT_STREAMS, R.string.layout_group_streams, "stream badges size addon logo"))
    add(link("layout-detail", SettingsPage.LAYOUT_DETAIL, R.string.layout_group_detail, "detail page episodes background cast"))
    add(link("layout-navigation", SettingsPage.LAYOUT_NAVIGATION, R.string.layout_group_navigation, "navigation bar tabs floating glow"))

    // ---- Home layout
    val home = SettingsPage.LAYOUT_HOME
    fun homeToggle(key: String, @StringRes group: Int, @StringRes title: Int, @StringRes detail: Int, icon: () -> androidx.compose.ui.graphics.vector.ImageVector,
                   on: (AppSettings) -> Boolean, set: (AppSettings, Boolean) -> AppSettings) =
        entry("home-$key", home, group, title, "home") {
            SettingToggle(stringResource(title), stringResource(detail), icon(), on(prefs), "pref-home-$key") { change(set(prefs, it)) }
        }
    add(homeToggle("hero", R.string.layout_group_home_layout, R.string.settings_home_hero, R.string.settings_home_hero_detail, { Icons.Filled.Home },
        { it.homeRows.hero }) { s, v -> s.copy(homeRows = s.homeRows.copy(hero = v)) })
    add(homeToggle("catalog-type", R.string.layout_group_home_layout, R.string.layout_catalog_type, R.string.layout_catalog_type_detail, { AppIcons.Explore },
        { it.layout.showCatalogType }) { s, v -> s.copy(layout = s.layout.copy(showCatalogType = v)) })
    add(homeToggle("hide-unreleased", R.string.layout_group_home_layout, R.string.layout_hide_unreleased, R.string.layout_hide_unreleased_detail, { Icons.Filled.Info },
        { it.layout.hideUnreleased }) { s, v -> s.copy(layout = s.layout.copy(hideUnreleased = v)) })
    add(homeToggle("for-you", R.string.layout_group_rows, R.string.home_for_you, R.string.settings_home_row_detail, { Icons.Filled.Star },
        { it.homeRows.forYou }) { s, v -> s.copy(homeRows = s.homeRows.copy(forYou = v)) })
    add(homeToggle("recent", R.string.layout_group_rows, R.string.home_recently_added, R.string.settings_home_row_detail, { Icons.Filled.List },
        { it.homeRows.recentlyAdded }) { s, v -> s.copy(homeRows = s.homeRows.copy(recentlyAdded = v)) })
    add(homeToggle("catalogs", R.string.layout_group_rows, R.string.settings_home_catalogs, R.string.settings_home_catalogs_detail, { AppIcons.Explore },
        { it.homeRows.catalogs }) { s, v -> s.copy(homeRows = s.homeRows.copy(catalogs = v)) })
    add(entry("home-hero-catalogs", home, R.string.layout_group_hero, R.string.layout_hero_catalogs, "hero carousel banner top", shown = { it.prefs.homeRows.hero }) { HeroCatalogList() })
    add(entry("home-catalog-list", home, R.string.layout_group_catalogs, R.string.layout_catalogs, "catalog rename reorder hide") { CatalogList() })

    // ---- Continue Watching
    val cw = SettingsPage.LAYOUT_CONTINUE
    add(entry("continue-show", cw, R.string.layout_group_visibility, R.string.layout_continue_show, "continue watching") {
        SettingToggle(stringResource(R.string.layout_continue_show), stringResource(R.string.layout_continue_show_detail), Icons.Filled.PlayArrow,
            prefs.homeRows.continueWatching, "pref-home-continue") { change(prefs.copy(homeRows = prefs.homeRows.copy(continueWatching = it))) }
    })
    add(entry("continue-style", cw, R.string.layout_group_card_style, R.string.layout_continue_style, "card wide poster") {
        Chips(stringResource(R.string.layout_continue_style), listOf(
            ContinueStyle.WIDE to stringResource(R.string.layout_continue_wide), ContinueStyle.POSTER to stringResource(R.string.layout_continue_poster),
        ), prefs.layout.continueStyle, "pref-continue-style") { change(prefs.copy(layout = prefs.layout.copy(continueStyle = it))) }
    })
    add(entry("continue-opens", cw, R.string.layout_group_behavior, R.string.layout_continue_opens, "on select resume details") {
        Chips(stringResource(R.string.layout_continue_opens), listOf(
            ContinueOpens.PLAY to stringResource(R.string.layout_continue_opens_play), ContinueOpens.DETAILS to stringResource(R.string.layout_continue_opens_details),
        ), prefs.layout.continueOpens, "pref-continue-opens") { change(prefs.copy(layout = prefs.layout.copy(continueOpens = it))) }
    })

    // ---- Poster card style
    val po = SettingsPage.LAYOUT_POSTERS
    add(entry("poster-preview", po, R.string.layout_group_poster_style, R.string.layout_poster_preview, "preview") { PosterPreview(prefs.layout) })
    add(entry("poster-width", po, R.string.layout_group_poster_style, R.string.layout_poster_width, "size big small") {
        Chips(stringResource(R.string.layout_poster_width), LayoutSettings.POSTER_WIDTHS.map { it to posterWidthLabel(it) }, prefs.layout.posterWidth, "pref-poster-width") {
            change(prefs.copy(layout = prefs.layout.copy(posterWidth = it)))
        }
    })
    add(entry("poster-radius", po, R.string.layout_group_poster_style, R.string.layout_poster_radius, "corners round") {
        Chips(stringResource(R.string.layout_poster_radius), listOf<Pair<Int?, String>>(null to stringResource(R.string.layout_radius_style)) +
            LayoutSettings.POSTER_RADII.map { it to radiusLabel(it) }, prefs.layout.posterRadius, "pref-poster-radius") {
            change(prefs.copy(layout = prefs.layout.copy(posterRadius = it)))
        }
    })
    add(entry("poster-labels", po, R.string.layout_group_poster_style, R.string.layout_hide_labels, "titles names") {
        SettingToggle(stringResource(R.string.layout_hide_labels), stringResource(R.string.layout_hide_labels_detail), AppIcons.Grid,
            prefs.layout.hideLabels, "pref-hide-labels") { change(prefs.copy(layout = prefs.layout.copy(hideLabels = it))) }
    })
    add(entry("poster-reset", po, R.string.layout_group_poster_style, R.string.layout_reset, "default") {
        SettingRow(stringResource(R.string.layout_reset), stringResource(R.string.layout_reset_posters_detail), Icons.Filled.Refresh, tag = "pref-poster-reset") {
            change(prefs.copy(layout = prefs.layout.copy(posterWidth = 120, posterRadius = null, hideLabels = false)))
        }
    })

    // ---- Streams
    val st = SettingsPage.LAYOUT_STREAMS
    add(entry("streams-size", st, R.string.layout_group_badges, R.string.layout_size_badges, "file size gb") {
        SettingToggle(stringResource(R.string.layout_size_badges), stringResource(R.string.layout_size_badges_detail), AppIcons.Grid,
            prefs.layout.sizeBadges, "pref-size-badges") { change(prefs.copy(layout = prefs.layout.copy(sizeBadges = it))) }
    })
    add(entry("streams-badge-position", st, R.string.layout_group_badges, R.string.layout_badge_position, "top bottom") {
        Chips(stringResource(R.string.layout_badge_position), listOf(true to stringResource(R.string.layout_badges_top), false to stringResource(R.string.layout_badges_bottom)),
            prefs.layout.badgesOnTop, "pref-badge-position") { change(prefs.copy(layout = prefs.layout.copy(badgesOnTop = it))) }
    })
    add(entry("streams-logo", st, R.string.layout_group_display, R.string.layout_addon_logo, "logo add-on name") {
        SettingToggle(stringResource(R.string.layout_addon_logo), stringResource(R.string.layout_addon_logo_detail), AppIcons.Explore,
            prefs.layout.addonLogo, "pref-addon-logo") { change(prefs.copy(layout = prefs.layout.copy(addonLogo = it))) }
    })

    // ---- Detail page
    val de = SettingsPage.LAYOUT_DETAIL
    add(entry("detail-background", de, R.string.layout_group_appearance, R.string.layout_detail_background, "cinematic blur") {
        Chips(stringResource(R.string.layout_detail_background), listOf(
            DetailBackground.NORMAL to stringResource(R.string.layout_background_normal), DetailBackground.CINEMATIC to stringResource(R.string.layout_background_cinematic),
        ), prefs.layout.detailBackground, "pref-detail-background", stringResource(R.string.layout_detail_background_detail)) {
            change(prefs.copy(layout = prefs.layout.copy(detailBackground = it)))
        }
    })
    add(entry("detail-episodes", de, R.string.layout_group_appearance, R.string.layout_episode_cards, "episodes list horizontal") {
        Chips(stringResource(R.string.layout_episode_cards), listOf(
            EpisodeCards.LIST to stringResource(R.string.layout_episodes_list), EpisodeCards.HORIZONTAL to stringResource(R.string.layout_episodes_horizontal),
        ), prefs.layout.episodeCards, "pref-episode-cards") { change(prefs.copy(layout = prefs.layout.copy(episodeCards = it))) }
    })
    add(entry("detail-overview", de, R.string.layout_group_sections, R.string.layout_show_overview, "description") {
        SettingToggle(stringResource(R.string.layout_show_overview), null, Icons.Filled.Info, prefs.layout.showOverview, "pref-show-overview") {
            change(prefs.copy(layout = prefs.layout.copy(showOverview = it)))
        }
    })
    add(entry("detail-cast", de, R.string.layout_group_sections, R.string.layout_show_cast, "actors") {
        SettingToggle(stringResource(R.string.layout_show_cast), null, Icons.Filled.Star, prefs.layout.showCast, "pref-show-cast") {
            change(prefs.copy(layout = prefs.layout.copy(showCast = it)))
        }
    })

    // ---- Navigation bar
    val na = SettingsPage.LAYOUT_NAVIGATION
    add(entry("nav-style", na, R.string.layout_group_nav_style, R.string.layout_nav_style, "adaptive panel floating classic") {
        Chips(stringResource(R.string.layout_nav_style), NavStyle.entries.map { it to navStyleLabel(it) }, prefs.layout.navStyle, "pref-nav-style",
            stringResource(R.string.layout_nav_style_detail)) { change(prefs.copy(layout = prefs.layout.copy(navStyle = it))) }
    })
    add(entry("nav-preview", na, R.string.layout_group_nav_style, R.string.layout_nav_preview, "preview try") { NavPreview(prefs) })
    add(entry("nav-glow", na, R.string.layout_group_nav_style, R.string.layout_nav_glow, "glow light",
        shown = { it.prefs.layout.navStyle.floating(it.prefs.style == UiStyle.MANGA) }) {
        SettingToggle(stringResource(R.string.layout_nav_glow), stringResource(R.string.layout_nav_glow_detail), AppIcons.Palette, prefs.layout.navGlow, "pref-nav-glow") {
            change(prefs.copy(layout = prefs.layout.copy(navGlow = it)))
        }
    })
    add(entry("dock-labels", na, R.string.layout_group_nav_style, R.string.settings_dock_labels, "tab names labels") {
        SettingToggle(stringResource(R.string.settings_dock_labels), stringResource(R.string.settings_dock_labels_detail), AppIcons.Grid, prefs.dockLabels, "pref-dock-labels") {
            change(prefs.copy(dockLabels = it))
        }
    })
}

/** The Layout pages' summaries on their rows ("Adaptive · Glow off", "Standard · 120 dp", …). */
@Composable
internal fun layoutSummary(page: SettingsPage, host: SettingsHost): String? {
    val l = host.prefs.layout
    return when (page) {
        SettingsPage.LAYOUT_HOME -> listOfNotNull(
            stringResource(if (host.prefs.homeRows.hero) R.string.layout_hero_on else R.string.layout_hero_off),
            stringResource(R.string.layout_catalog_type).takeIf { l.showCatalogType },
        ).joinToString(" · ")
        SettingsPage.LAYOUT_CONTINUE -> if (!host.prefs.homeRows.continueWatching) stringResource(R.string.layout_hidden) else listOf(
            stringResource(if (l.continueStyle == ContinueStyle.WIDE) R.string.layout_continue_wide else R.string.layout_continue_poster),
            stringResource(if (l.continueOpens == ContinueOpens.PLAY) R.string.layout_continue_opens_play else R.string.layout_continue_opens_details),
        ).joinToString(" · ")
        SettingsPage.LAYOUT_POSTERS -> listOf(posterWidthLabel(l.posterWidth), l.posterRadius?.let { radiusLabel(it) } ?: stringResource(R.string.layout_radius_style))
            .joinToString(" · ")
        SettingsPage.LAYOUT_STREAMS -> stringResource(R.string.layout_streams_detail)
        SettingsPage.LAYOUT_DETAIL -> listOf(
            stringResource(if (l.detailBackground == DetailBackground.NORMAL) R.string.layout_background_normal else R.string.layout_background_cinematic),
            stringResource(if (l.episodeCards == EpisodeCards.LIST) R.string.layout_episodes_list else R.string.layout_episodes_horizontal),
        ).joinToString(" · ")
        SettingsPage.LAYOUT_NAVIGATION -> listOfNotNull(
            navStyleLabel(l.navStyle),
            stringResource(if (l.navGlow) R.string.layout_glow_on else R.string.layout_glow_off).takeIf { l.navStyle.floating(host.prefs.style == UiStyle.MANGA) },
        ).joinToString(" · ")
        else -> null
    }
}

@Composable
internal fun navStyleLabel(s: NavStyle) = stringResource(when (s) {
    NavStyle.ADAPTIVE -> R.string.layout_nav_adaptive
    NavStyle.EXPANDED -> R.string.layout_nav_expanded
    NavStyle.COMPACT -> R.string.layout_nav_compact
    NavStyle.PANELS -> R.string.layout_nav_panels
    NavStyle.CLASSIC -> R.string.layout_nav_classic
})

/** Nuvio's names for the width steps, with the size. */
@Composable
internal fun posterWidthLabel(dp: Int): String {
    val name = when (dp) {
        104 -> R.string.layout_width_compact; 112 -> R.string.layout_width_dense; 120 -> R.string.layout_width_standard
        126 -> R.string.layout_width_balanced; 134 -> R.string.layout_width_comfort; else -> R.string.layout_width_large
    }
    return stringResource(R.string.layout_width_value, stringResource(name), dp)
}

@Composable
internal fun radiusLabel(dp: Int): String = stringResource(when (dp) {
    0 -> R.string.layout_radius_sharp; 4 -> R.string.layout_radius_subtle; 8 -> R.string.layout_radius_classic
    12 -> R.string.layout_radius_rounded; else -> R.string.layout_radius_pill
})

/** A titled single choice as chips (wraps on narrow screens). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Chips(title: String, options: List<Pair<T, String>>, selected: T, tag: String, detail: String? = null, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) },
                    leadingIcon = if (value == selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
                    modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("$tag:$label"),
                )
            }
        }
    }
}

/**
 * The navigation bar as chosen, to try (Nuvio's preview): tap a tab; scroll the strip above it
 * to see Adaptive shrink and grow.
 */
@Composable
private fun NavPreview(prefs: AppSettings) {
    var tab by remember { mutableStateOf(dev.yourstream.ui.screens.navigation.TopLevel.HOME) }
    var compact by remember { mutableStateOf(false) }
    val manga = prefs.style == UiStyle.MANGA
    val connection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.x < -4f) compact = true else if (available.x > 4f) compact = false
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("nav-preview"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().nestedScroll(connection).horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(12) { androidx.compose.material3.Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(width = 56.dp, height = 40.dp)) {} }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalAppSettings provides prefs) {
            dev.yourstream.ui.screens.navigation.MainNavigationBar(tab, { tab = it }, compactHint = compact && prefs.layout.navStyle == NavStyle.ADAPTIVE && !manga)
        }
        Text(stringResource(R.string.layout_nav_preview_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** A live poster with the chosen width, corners and labels. */
@Composable
private fun PosterPreview(l: LayoutSettings) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("poster-preview"), contentAlignment = Alignment.Center) {
        androidx.compose.runtime.CompositionLocalProvider(LocalCardLook provides CardLook(l.posterRadius?.dp, l.hideLabels)) {
            PosterCard(MediaCardModel("preview", stringResource(R.string.layout_preview_title), "2024", null), {}, Modifier.width(l.posterWidth.dp))
        }
    }
}

// ---------------------------------------------------------------- Home layout → Catalogs

/** One add-on catalog as the Home layout page lists it. */
data class CatalogChoice(val key: String, val name: String, val addon: String, val type: CatalogType, val shown: Boolean, val customName: String?, val hero: Boolean = false)

@HiltViewModel
class HomeCatalogsViewModel @Inject constructor(
    private val addons: dev.yourstream.catalog.sync.CatalogAddons,
    private val prefs: HomeCatalogPrefs,
) : ViewModel() {
    private val all = MutableStateFlow<List<CatalogChoice>?>(null)

    /** Every catalog of the enabled add-ons, in Home's order (null while loading). */
    val catalogs: StateFlow<List<CatalogChoice>?> = combine(all, prefs.layout) { list, l -> list?.let { arrange(it, l) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val names = addons.addons.value.associate { it.id to it.name }
            all.value = runCatching { addons.manager().catalogs() }.getOrDefault(emptyList()).map { c ->
                CatalogChoice(ScreensModule.catalogKey(c), c.name, names[c.provider.value] ?: c.provider.value, c.type, true, null)
            }
        }
    }

    private fun arrange(list: List<CatalogChoice>, l: HomeCatalogLayout) =
        list.sortedBy { c -> l.order.indexOf(c.key).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { it.copy(shown = it.key !in l.hidden, customName = l.names[it.key], hero = it.key in l.hero) }

    fun setShown(key: String, shown: Boolean) = prefs.update { l -> l.copy(hidden = if (shown) l.hidden - key else l.hidden + key) }
    fun move(key: String, by: Int) = prefs.update { l -> l.move(catalogs.value.orEmpty().map { it.key }, key, by) }
    fun rename(key: String, name: String) = prefs.update { l -> l.copy(names = if (name.isBlank()) l.names - key else l.names + (key to name.trim())) }
    fun reset() = prefs.reset()
    fun setHero(key: String, on: Boolean) = prefs.update { it.withHero(key, on) }
}

/**
 * Settings → Layout → Home layout → Hero catalogs (Nuvio's): "N of M selected", opening to every
 * catalog with a switch; at most [HomeCatalogLayout.HERO_LIMIT].
 */
@Composable
private fun HeroCatalogList(vm: HomeCatalogsViewModel = hiltViewModel()) {
    val list by vm.catalogs.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val all = list.orEmpty()
    val selected = all.count { it.hero }
    Column(Modifier.fillMaxWidth().testTag("hero-catalogs")) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = all.isNotEmpty()) { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("hero-catalogs-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.layout_hero_selected, selected, all.size), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (selected == 0) R.string.layout_hero_none else R.string.layout_hero_limit, HomeCatalogLayout.HERO_LIMIT),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        if (open) all.forEach { c ->
            val canTurnOn = c.hero || selected < HomeCatalogLayout.HERO_LIMIT
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).testTag("hero-catalog:${c.key}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.customName ?: c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.addon, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Switch(checked = c.hero, onCheckedChange = { vm.setHero(c.key, it) }, enabled = canTurnOn,
                    modifier = Modifier.focusHighlight().testTag("hero-catalog-on:${c.key}"))
            }
        }
    }
}

@Composable
private fun CatalogList(vm: HomeCatalogsViewModel = hiltViewModel()) {
    val list by vm.catalogs.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<CatalogChoice?>(null) }
    val movies = stringResource(R.string.nav_movies)
    val series = stringResource(R.string.nav_series)
    Column(Modifier.fillMaxWidth().testTag("home-catalog-list")) {
        when {
            list == null -> Text(stringResource(R.string.layout_catalogs_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            list!!.isEmpty() -> Text(stringResource(R.string.layout_catalogs_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
            else -> {
                Text(stringResource(R.string.layout_catalogs_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                list!!.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("home-catalog:${c.key}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.customName ?: c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${c.addon} · ${if (c.type == CatalogType.MOVIE) movies else series}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { renaming = c }, modifier = Modifier.focusHighlight().testTag("home-catalog-rename:${c.key}")) {
                            Icon(Icons.Filled.Edit, stringResource(R.string.layout_catalog_rename, c.name))
                        }
                        IconButton(onClick = { vm.move(c.key, -1) }, enabled = i > 0, modifier = Modifier.focusHighlight().testTag("home-catalog-up:${c.key}")) {
                            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.addons_move_up, c.name))
                        }
                        IconButton(onClick = { vm.move(c.key, 1) }, enabled = i < list!!.lastIndex, modifier = Modifier.focusHighlight().testTag("home-catalog-down:${c.key}")) {
                            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.addons_move_down, c.name))
                        }
                        Switch(checked = c.shown, onCheckedChange = { vm.setShown(c.key, it) }, modifier = Modifier.focusHighlight().testTag("home-catalog-shown:${c.key}"))
                    }
                }
                SettingRow(stringResource(R.string.layout_reset), stringResource(R.string.layout_reset_catalogs_detail), Icons.Filled.Refresh, tag = "home-catalogs-reset") { vm.reset() }
            }
        }
    }
    renaming?.let { c ->
        var text by remember(c.key) { mutableStateOf(c.customName ?: c.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.layout_catalog_rename, c.name)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("home-catalog-name")) },
            confirmButton = { TextButton(onClick = { vm.rename(c.key, if (text.trim() == c.name) "" else text); renaming = null }, modifier = Modifier.testTag("home-catalog-name-save")) {
                Text(stringResource(R.string.layout_save))
            } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.addons_delete_no)) } },
        )
    }
}
