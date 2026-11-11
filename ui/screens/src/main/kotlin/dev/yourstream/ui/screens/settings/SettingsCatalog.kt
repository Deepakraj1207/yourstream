package dev.yourstream.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.yourstream.ui.core.AppIcons
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.player.label

/** The hub's sections, in Nuvio's order. */
enum class SettingsSection(@StringRes val title: Int) {
    YOU(R.string.settings_group_you),
    LOOK(R.string.settings_section_look),
    WATCH(R.string.settings_section_watch),
    SERVICES(R.string.settings_section_services),
    SYSTEM(R.string.settings_section_system),
}

/** A category of Settings: a row on the hub that opens its own page. */
enum class SettingsPage(val section: SettingsSection, @StringRes val title: Int, @StringRes val detail: Int) {
    APPEARANCE(SettingsSection.LOOK, R.string.settings_group_appearance, R.string.settings_page_appearance_detail),
    LAYOUT(SettingsSection.LOOK, R.string.settings_page_layout, R.string.settings_page_layout_detail),
    CONTENT(SettingsSection.WATCH, R.string.settings_page_content, R.string.settings_page_content_detail),
    PLAYBACK(SettingsSection.WATCH, R.string.settings_group_playback, R.string.settings_page_playback_detail),
    INTEGRATIONS(SettingsSection.SERVICES, R.string.settings_page_integrations, R.string.settings_page_integrations_detail),
    ADVANCED(SettingsSection.SYSTEM, R.string.settings_page_advanced, R.string.settings_page_advanced_detail),
    ABOUT(SettingsSection.SYSTEM, R.string.settings_group_about, R.string.settings_page_about_detail);

    val icon: ImageVector get() = when (this) {
        APPEARANCE -> AppIcons.Palette
        LAYOUT -> AppIcons.Grid
        CONTENT -> AppIcons.Explore
        PLAYBACK -> Icons.Filled.PlayArrow
        INTEGRATIONS -> AppIcons.Link
        ADVANCED -> Icons.Filled.Build
        ABOUT -> Icons.Filled.Info
    }
}

/** Choices that open a dialog. */
enum class SettingsDialog { SEEK, FIT, HOLD, SUBTITLE, STYLE, THEME, POSTER, BUFFER, RESET }

/** What a setting's row works with: the settings bundle, and the dialogs it may open. */
class SettingsRows internal constructor(val host: SettingsHost, internal val open: (SettingsDialog) -> Unit) {
    val prefs: AppSettings get() = host.prefs
    val state: SettingsUiState get() = host.state
    fun change(new: AppSettings) = host.onPrefs(new)
}

/**
 * One setting: where it lives ([page], or the hub's [section] when [page] is null), the group
 * it is listed under, its name (what search matches, with [keywords]) and its row.
 */
internal class SettingEntry(
    val key: String,
    val section: SettingsSection,
    val page: SettingsPage?,
    @StringRes val group: Int,
    @StringRes val title: Int,
    val keywords: String = "",
    val shown: (SettingsHost) -> Boolean = { true },
    val row: @Composable SettingsRows.() -> Unit,
)

private fun entry(
    key: String, page: SettingsPage, @StringRes group: Int, @StringRes title: Int, keywords: String = "",
    shown: (SettingsHost) -> Boolean = { true }, row: @Composable SettingsRows.() -> Unit,
) = SettingEntry(key, page.section, page, group, title, keywords, shown, row)

/** An open-source component the app is built on, and its license. */
internal data class License(val name: String, val what: String, val license: String)

internal val LICENSES = listOf(
    License("YourStream", "This app; its source code is on GitHub", "GNU GPL 3.0"),
    License("TDLib", "Telegram's client library", "Boost Software License 1.0"),
    License("tdl-coroutines", "Kotlin bindings for TDLib", "Apache License 2.0"),
    License("AndroidX, Jetpack Compose, Media3", "Interface, navigation, database, player", "Apache License 2.0"),
    License("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Language and core libraries", "Apache License 2.0"),
    License("Dagger and Hilt", "Dependency injection", "Apache License 2.0"),
    License("OkHttp", "HTTP client, DNS over HTTPS", "Apache License 2.0"),
    License("Ktor", "HTTP client for add-ons and TMDB", "Apache License 2.0"),
    License("Coil", "Image loading", "Apache License 2.0"),
    License("libtorrent, libtorrent4j", "Torrent streaming", "BSD 3-Clause (libtorrent), MIT (libtorrent4j)"),
    License("FFmpeg decoder for Media3 (Jellyfin build)", "Audio formats the device lacks", "GNU GPL 3.0"),
    License("ZXing", "QR codes for sign-in", "Apache License 2.0"),
    License("Material icons", "Icons", "Apache License 2.0"),
    License("Bangers", "Manga lettering font", "SIL Open Font License 1.1"),
)

/** Every setting, in the order pages list them. The hub, the pages and search all render from here. */
internal val SETTINGS: List<SettingEntry> = buildList {
    // ---- You (on the hub, under the profile card)
    add(SettingEntry("stats", SettingsSection.YOU, null, R.string.settings_group_you, R.string.settings_stats, "history watched time",
        shown = { it.links.stats != null }) {
        SettingRow(stringResource(R.string.settings_stats), stringResource(R.string.settings_stats_detail), Icons.Filled.DateRange, onClick = host.links.stats)
    })

    // ---- Appearance
    val ap = SettingsPage.APPEARANCE
    add(entry("style", ap, R.string.settings_group_display, R.string.settings_style, "manga classic look") {
        SettingRow(stringResource(R.string.settings_style), styleLabel(prefs.style), Icons.Filled.Star, tag = "pref-style") { open(SettingsDialog.STYLE) }
    })
    add(entry("theme", ap, R.string.settings_group_display, R.string.settings_theme, "dark light night mode") {
        SettingRow(stringResource(R.string.settings_theme), themeLabel(prefs.theme), Icons.Filled.Settings, tag = "pref-theme") { open(SettingsDialog.THEME) }
    })
    add(entry("amoled", ap, R.string.settings_group_display, R.string.settings_amoled, "oled pure black dark") {
        SettingToggle(stringResource(R.string.settings_amoled), stringResource(R.string.settings_amoled_detail), AppIcons.Brightness, prefs.amoledBlack, "pref-amoled") {
            change(prefs.copy(amoledBlack = it))
        }
    })
    add(entry("dock-labels", ap, R.string.settings_group_navigation, R.string.settings_dock_labels, "navigation bar tabs labels names") {
        SettingToggle(stringResource(R.string.settings_dock_labels), stringResource(R.string.settings_dock_labels_detail), AppIcons.Dock, prefs.dockLabels, "pref-dock-labels") {
            change(prefs.copy(dockLabels = it))
        }
    })

    // ---- Layout
    val la = SettingsPage.LAYOUT
    fun homeRow(key: String, @StringRes title: Int, @StringRes detail: Int, icon: () -> ImageVector, on: (HomeRowsShown) -> Boolean, set: (HomeRowsShown, Boolean) -> HomeRowsShown) =
        entry("home-$key", la, R.string.settings_group_home, title, "home rows") {
            SettingToggle(stringResource(title), stringResource(detail), icon(), on(prefs.homeRows), "pref-home-$key") {
                change(prefs.copy(homeRows = set(prefs.homeRows, it)))
            }
        }
    add(homeRow("hero", R.string.settings_home_hero, R.string.settings_home_hero_detail, { Icons.Filled.Home }, { it.hero }) { r, v -> r.copy(hero = v) })
    add(homeRow("continue", R.string.home_continue, R.string.settings_home_row_detail, { Icons.Filled.PlayArrow }, { it.continueWatching }) { r, v -> r.copy(continueWatching = v) })
    add(homeRow("for-you", R.string.home_for_you, R.string.settings_home_row_detail, { Icons.Filled.Star }, { it.forYou }) { r, v -> r.copy(forYou = v) })
    add(homeRow("recent", R.string.home_recently_added, R.string.settings_home_row_detail, { Icons.Filled.DateRange }, { it.recentlyAdded }) { r, v -> r.copy(recentlyAdded = v) })
    add(homeRow("series", R.string.home_series, R.string.settings_home_row_detail, { AppIcons.Tv }, { it.series }) { r, v -> r.copy(series = v) })
    add(homeRow("catalogs", R.string.settings_home_catalogs, R.string.settings_home_catalogs_detail, { AppIcons.Explore }, { it.catalogs }) { r, v -> r.copy(catalogs = v) })
    add(entry("poster-size", la, R.string.settings_group_posters, R.string.settings_poster_size, "cards grid big small") {
        SettingRow(stringResource(R.string.settings_poster_size), posterSizeLabel(prefs.posterSize), AppIcons.Grid, tag = "pref-poster-size") { open(SettingsDialog.POSTER) }
    })

    // ---- Content & discovery
    val co = SettingsPage.CONTENT
    add(entry("addons", co, R.string.settings_group_sources, R.string.settings_addons, "stremio nuvio catalogs streams", shown = { it.links.addons != null }) {
        SettingRow(stringResource(R.string.settings_addons), stringResource(R.string.settings_addons_detail), AppIcons.Explore, tag = "settings-addons", onClick = host.links.addons)
    })
    add(entry("sources", co, R.string.settings_group_sources, R.string.settings_sources, "telegram chats channels groups") {
        SettingRow(stringResource(R.string.settings_sources), pluralStringResource(R.plurals.settings_sources_count, state.enabledSources, state.enabledSources),
            Icons.AutoMirrored.Filled.List, tag = "settings-sources", onClick = host.onSources)
    })
    add(entry("library-status", co, R.string.settings_group_library, R.string.settings_status, "indexing sync matching progress", shown = { it.links.libraryStatus != null }) {
        SettingRow(stringResource(R.string.settings_status), stringResource(R.string.settings_status_detail), AppIcons.Episodes, onClick = host.links.libraryStatus)
    })
    add(entry("review", co, R.string.settings_group_library, R.string.settings_review, "matches titles wrong", shown = { it.links.review != null && it.state.reviewCount > 0 }) {
        SettingRow(stringResource(R.string.settings_review), pluralStringResource(R.plurals.settings_review_count, state.reviewCount, state.reviewCount),
            Icons.Filled.Warning, onClick = host.links.review)
    })

    // ---- Playback
    val pl = SettingsPage.PLAYBACK
    add(entry("fit", pl, R.string.settings_group_player, R.string.settings_fit, "zoom stretch crop aspect") {
        SettingRow(stringResource(R.string.settings_fit), prefs.defaultFit.label(), AppIcons.AspectRatio, tag = "pref-fit") { open(SettingsDialog.FIT) }
    })
    add(entry("landscape", pl, R.string.settings_group_player, R.string.settings_landscape, "rotate orientation") {
        SettingToggle(stringResource(R.string.settings_landscape), stringResource(R.string.settings_landscape_detail), AppIcons.ScreenRotation, prefs.playerLandscape, "pref-landscape") {
            change(prefs.copy(playerLandscape = it))
        }
    })
    add(entry("pip", pl, R.string.settings_group_player, R.string.settings_pip, "pip floating window") {
        SettingToggle(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_detail), AppIcons.Episodes, prefs.pictureInPicture, "pref-pip") {
            change(prefs.copy(pictureInPicture = it))
        }
    })
    add(entry("seek", pl, R.string.settings_group_controls, R.string.settings_seek, "skip forward back double tap") {
        SettingRow(stringResource(R.string.settings_seek), stringResource(R.string.settings_seconds, prefs.seekSeconds), AppIcons.FastForward, tag = "pref-seek") { open(SettingsDialog.SEEK) }
    })
    add(entry("hold", pl, R.string.settings_group_controls, R.string.settings_hold_speed, "speed fast long press") {
        SettingRow(stringResource(R.string.settings_hold_speed), speedLabel(prefs.holdSpeed), AppIcons.Speed, tag = "pref-hold") { open(SettingsDialog.HOLD) }
    })
    add(entry("autoplay", pl, R.string.settings_group_up_next, R.string.settings_autoplay, "next episode binge") {
        SettingToggle(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_detail), Icons.Filled.PlayArrow, prefs.autoplayNext, "pref-autoplay") {
            change(prefs.copy(autoplayNext = it))
        }
    })
    add(entry("languages", pl, R.string.settings_languages, R.string.settings_preferred_languages, "audio subtitles language tamil hindi english") {
        SettingRow(stringResource(R.string.settings_preferred_languages), state.languages.ifEmpty { stringResource(R.string.settings_preferred_languages_hint) },
            AppIcons.Audio, tag = "settings-languages", onClick = host.onLanguages)
    })
    add(entry("subtitle-size", pl, R.string.settings_languages, R.string.settings_subtitle_size, "captions text") {
        SettingRow(stringResource(R.string.settings_subtitle_size), subtitleSizeLabel(prefs.subtitleScale), AppIcons.Subtitles, tag = "pref-subtitle-size") { open(SettingsDialog.SUBTITLE) }
    })
    add(entry("subtitle-box", pl, R.string.settings_languages, R.string.settings_subtitle_box, "captions background outline") {
        SettingToggle(stringResource(R.string.settings_subtitle_box), stringResource(R.string.settings_subtitle_box_detail), AppIcons.Subtitles, prefs.subtitleBox, "pref-subtitle-box") {
            change(prefs.copy(subtitleBox = it))
        }
    })
    add(entry("ffmpeg", pl, R.string.settings_group_decoders, R.string.settings_app_decoders, "ffmpeg sound audio dts ac3 eac3 truehd no sound decoder") {
        SettingToggle(stringResource(R.string.settings_app_decoders), stringResource(R.string.settings_app_decoders_detail), AppIcons.Volume, prefs.appAudioDecodersFirst, "pref-ffmpeg") {
            change(prefs.copy(appAudioDecodersFirst = it))
        }
    })
    add(entry("buffer", pl, R.string.settings_group_streaming, R.string.settings_buffer, "cache buffering preload") {
        SettingRow(stringResource(R.string.settings_buffer), stringResource(R.string.settings_buffer_value, bufferLabel(prefs.streamBufferSeconds)), AppIcons.Speed,
            tag = "pref-buffer") { open(SettingsDialog.BUFFER) }
    })
    add(entry("torrents", pl, R.string.settings_group_streaming, R.string.settings_torrents, "p2p bittorrent magnet peers") {
        SettingToggle(stringResource(R.string.settings_torrents), stringResource(R.string.settings_torrents_detail), AppIcons.Update, prefs.torrentStreaming, "pref-torrents") {
            change(prefs.copy(torrentStreaming = it))
        }
    })

    // ---- Integrations
    val ig = SettingsPage.INTEGRATIONS
    add(entry("tmdb", ig, R.string.settings_group_metadata, R.string.tmdb_title, "tmdb api key posters metadata", shown = { it.links.tmdb != null }) {
        SettingRow(stringResource(R.string.tmdb_title), stringResource(R.string.settings_tmdb_detail), AppIcons.Movie, tag = "settings-tmdb", onClick = host.links.tmdb)
    })
    add(entry("secure-dns", ig, R.string.settings_group_network, R.string.settings_secure_dns, "dns doh blocked network jio isp tmdb") {
        val detail = when {
            prefs.secureDns -> stringResource(R.string.settings_secure_dns_on)
            host.securedHosts.isNotEmpty() -> stringResource(R.string.settings_secure_dns_auto) + "\n" +
                stringResource(R.string.settings_secure_dns_now, host.securedHosts.take(3).joinToString(", "))
            else -> stringResource(R.string.settings_secure_dns_auto)
        }
        SettingToggle(stringResource(R.string.settings_secure_dns), detail, Icons.Filled.Lock, prefs.secureDns, "pref-secure-dns") { change(prefs.copy(secureDns = it)) }
    })

    // ---- Services (on the hub): progress sync, after the Integrations page
    add(SettingEntry("sync", SettingsSection.SERVICES, null, R.string.settings_section_services, R.string.settings_sync, "progress devices saved messages trakt",
        shown = { it.links.sync != null }) {
        SettingRow(stringResource(R.string.settings_sync), stringResource(if (state.syncOn) R.string.settings_sync_on else R.string.settings_sync_off),
            Icons.Filled.Refresh, tag = "settings-sync", onClick = host.links.sync)
    })

    // ---- Advanced
    val ad = SettingsPage.ADVANCED
    add(entry("clear-cache", ad, R.string.settings_group_maintenance, R.string.settings_clear_cache, "images posters storage space", shown = { it.onClearCache != null }) {
        SettingRow(stringResource(R.string.settings_clear_cache), stringResource(R.string.settings_clear_cache_detail), Icons.Filled.Delete, onClick = host.onClearCache)
    })
    add(entry("reset", ad, R.string.settings_group_maintenance, R.string.settings_reset, "defaults restore", shown = { it.onReset != null }) {
        SettingRow(stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_detail), Icons.Filled.Refresh, tag = "pref-reset") { open(SettingsDialog.RESET) }
    })

    // ---- About
    val ab = SettingsPage.ABOUT
    add(entry("version", ab, R.string.settings_group_app, R.string.app_name, "version") {
        SettingRow(stringResource(R.string.app_name), host.appVersion?.let { stringResource(R.string.settings_version, it) } ?: stringResource(R.string.settings_about_line), Icons.Filled.Info)
    })
    add(entry("update", ab, R.string.settings_group_app, R.string.update_row, "update upgrade new version apk", shown = { it.onUpdate != null }) {
        SettingRow(stringResource(R.string.update_row), host.updateLine, AppIcons.Update, tag = "update-row", onClick = host.onUpdate)
    })
    add(entry("source", ab, R.string.settings_group_app, R.string.settings_source, "github open source code", shown = { it.onSourceCode != null }) {
        SettingRow(stringResource(R.string.settings_source), stringResource(R.string.settings_source_detail), Icons.Filled.Share, onClick = host.onSourceCode)
    })
    LICENSES.forEachIndexed { i, l ->
        add(entry("license-$i", ab, R.string.settings_group_licenses, R.string.settings_group_licenses, "license licence open source ${l.name} ${l.what}") {
            SettingRow(l.name, "${l.what} · ${l.license}", null)
        })
    }
}

/** The hub's summary under a page's row: the page's own description, or what it is set to. */
@Composable
internal fun pageSummary(page: SettingsPage, host: SettingsHost): String = when (page) {
    SettingsPage.APPEARANCE -> listOfNotNull(styleLabel(host.prefs.style), themeLabel(host.prefs.theme), stringResource(R.string.settings_amoled).takeIf { host.prefs.amoledBlack })
        .joinToString(" · ")
    SettingsPage.CONTENT -> listOf(pluralStringResource(R.plurals.settings_sources_count, host.state.enabledSources, host.state.enabledSources), stringResource(R.string.settings_addons))
        .joinToString(" · ")
    SettingsPage.ABOUT -> host.updateLine ?: host.appVersion?.let { stringResource(R.string.settings_version, it) } ?: stringResource(page.detail)
    else -> stringResource(page.detail)
}

internal fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "×"

@Composable
internal fun bufferLabel(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.settings_seconds, seconds) else pluralStringResource(R.plurals.settings_minutes, seconds / 60, seconds / 60)

@Composable
internal fun posterSizeLabel(s: PosterSize) = stringResource(
    when (s) {
        PosterSize.SMALL -> R.string.settings_poster_small
        PosterSize.MEDIUM -> R.string.settings_poster_medium
        PosterSize.LARGE -> R.string.settings_poster_large
    },
)

@Composable
internal fun subtitleSizeLabel(scale: Float) = stringResource(
    when {
        scale < 0.9f -> R.string.settings_subtitle_small
        scale < 1.1f -> R.string.settings_subtitle_normal
        scale < 1.3f -> R.string.settings_subtitle_large
        scale < 1.6f -> R.string.settings_subtitle_larger
        else -> R.string.settings_subtitle_huge
    },
)

@Composable
internal fun styleLabel(s: UiStyle) = stringResource(if (s == UiStyle.MANGA) R.string.settings_style_manga else R.string.settings_style_classic)

@Composable
internal fun themeLabel(t: ThemeMode) = stringResource(
    when (t) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.DARK -> R.string.settings_theme_dark
        ThemeMode.LIGHT -> R.string.settings_theme_light
    },
)
