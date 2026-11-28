package dev.yourstream.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.ui.screens.player.VideoFit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** The app's look: manga / comic-store (default) or the plain classic style. */
enum class UiStyle { MANGA, CLASSIC }

/** Poster card size in rows and grids (Settings → Layout), as a factor of the layout's own width. */

/** Continue Watching cards: info-dense landscape cards, or posters. */
enum class ContinueStyle { WIDE, POSTER }

/** What a Continue Watching card does when tapped. */
enum class ContinueOpens { PLAY, DETAILS }

/** The title pages' background: plain, or the artwork blurred behind the header. */
enum class DetailBackground { NORMAL, CINEMATIC }

/** A title page's episodes: a vertical list, or a horizontal row of cards. */
enum class EpisodeCards { LIST, HORIZONTAL }

/**
 * The phone navigation bar, as Nuvio offers it. ADAPTIVE: Nuvio's floating bar that shrinks to
 * icons while scrolling down and grows back when scrolling up (in the manga look: the Panel
 * Grid); EXPANDED / COMPACT: the floating bar always with names / always icons only; PANELS: the
 * manga panel grid; CLASSIC: the Material bar under the content.
 */
enum class NavStyle { ADAPTIVE, EXPANDED, COMPACT, PANELS, CLASSIC;
    /** What the bar is in [manga] look: floating (and whether it adapts), panels or classic. */
    fun floating(manga: Boolean): Boolean = this == EXPANDED || this == COMPACT || (this == ADAPTIVE && !manga)
}

/**
 * Settings → Layout, in Nuvio's structure: Home layout, Continue Watching, poster cards, streams,
 * the title page and the navigation bar. Each option changes those screens (nothing is cosmetic
 * only in the settings).
 */
data class LayoutSettings(
    /** Home's add-on rows say what they hold: "Popular · Movies". */
    val showCatalogType: Boolean = false,
    /** Add-on rows leave out titles from future years (not out yet). */
    val hideUnreleased: Boolean = false,
    val continueStyle: ContinueStyle = ContinueStyle.WIDE,
    val continueOpens: ContinueOpens = ContinueOpens.PLAY,
    /** Poster card width in dp on phones (other layouts scale with it); see [POSTER_WIDTHS]. */
    val posterWidth: Int = 120,
    /** Poster corner radius in dp; null: the look's own shape. See [POSTER_RADII]. */
    val posterRadius: Int? = null,
    /** Posters without the title under them. */
    val hideLabels: Boolean = false,
    /** Stream results show the file size as a badge, when the add-on says it. */
    val sizeBadges: Boolean = true,
    /** Stream badges above the description (else below it). */
    val badgesOnTop: Boolean = false,
    /** Each add-on's logo next to its name over its streams. */
    val addonLogo: Boolean = true,
    val detailBackground: DetailBackground = DetailBackground.NORMAL,
    val episodeCards: EpisodeCards = EpisodeCards.LIST,
    /** The title page shows the description. */
    val showOverview: Boolean = true,
    /** The title page shows the cast. */
    val showCast: Boolean = true,
    val navStyle: NavStyle = NavStyle.ADAPTIVE,
    /** Soft light around the floating navigation bar. */
    val navGlow: Boolean = false,
) {
    /** Poster width relative to the phone default (120 dp), for every layout. */
    val posterScale: Float get() = posterWidth / 120f

    companion object {
        /** Nuvio's width steps: Compact, Dense, Standard, Balanced, Comfort, Large. */
        val POSTER_WIDTHS = listOf(104, 112, 120, 126, 134, 140)
        /** Sharp, Subtle, Classic, Rounded, Pill. */
        val POSTER_RADII = listOf(0, 4, 8, 12, 16)
    }
}

/** Which rows Home shows (Settings → Layout). All on by default. */
data class HomeRowsShown(
    val hero: Boolean = true,
    val continueWatching: Boolean = true,
    val forYou: Boolean = true,
    val recentlyAdded: Boolean = true,
    val series: Boolean = true,
    val catalogs: Boolean = true,
)

/** App-wide choices from Settings (stored on the device). */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val style: UiStyle = UiStyle.MANGA,
    /** Double-tap, arrow keys and the ±buttons seek this far. */
    val seekSeconds: Int = 10,
    /** Start the next episode when one ends (after a short countdown). */
    val autoplayNext: Boolean = true,
    /** How videos fill the screen when the player opens. */
    val defaultFit: VideoFit = VideoFit.FIT,
    /** Phones/tablets: the player opens in landscape. */
    val playerLandscape: Boolean = true,
    /** Speed while holding a finger on the video. */
    val holdSpeed: Float = 2f,
    /** Leaving the app while a video plays keeps it playing in a floating window. */
    val pictureInPicture: Boolean = true,
    /**
     * Leaving the app or turning the screen off keeps the sound playing, with controls in the
     * notification and on the lock screen. Off: playback pauses on leaving.
     */
    val backgroundPlayback: Boolean = true,
    /** Subtitle text size relative to the player's default. */
    val subtitleScale: Float = 1f,
    /** Subtitles on a dark box (else white text with a black outline). */
    val subtitleBox: Boolean = false,
    /** Subtitle text colour (ARGB), one of [SUBTITLE_COLORS]. */
    val subtitleColor: Int = SUBTITLE_COLORS.first(),
    val subtitleBold: Boolean = false,
    /** Distance of subtitles from the bottom, as a fraction of the video's height ([SUBTITLE_OFFSETS]). */
    val subtitleOffset: Float = 0.08f,
    /**
     * Torrent results from stream add-ons play with the built-in torrent engine. Off until the
     * user agrees (peers see the device's IP address): [TorrentConsentDialog].
     */
    val torrentStreaming: Boolean = false,
    /** Resolve every host through DNS over HTTPS. */
    val secureDns: Boolean = false,
    /** Hosts the network's DNS blocks are looked up through DNS over HTTPS (Cloudflare, Google). */
    val secureDnsFallback: Boolean = true,
    /** Posters from blocked image hosts are fetched through public image proxies (wsrv.nl, i0.wp.com). */
    val imageProxies: Boolean = true,
    /** Dark theme on pure black (OLED screens): backgrounds and cards' surroundings turn #000. */
    val amoledBlack: Boolean = false,
    /** The phone's navigation bar shows every tab's name under its icon (else only the selected one's). */
    val dockLabels: Boolean = true,
    /**
     * Less movement: pages cross-fade instead of sliding, loading placeholders and the indexing
     * icon stay still. The system "Remove animations" option has the same effect.
     */
    val reduceMotion: Boolean = false,
    /** Haptic feedback on taps, toggles and player gestures (the phone's own setting also applies). */
    val haptics: Boolean = true,
    val homeRows: HomeRowsShown = HomeRowsShown(),
    val layout: LayoutSettings = LayoutSettings(),
    /** The title page's stream Sort & filter choice (kept for every title). */
    val streamFilter: dev.yourstream.ui.screens.addon.StreamFilter = dev.yourstream.ui.screens.addon.StreamFilter(),
    /** Add-on and torrent streams: how far ahead the player buffers (within its memory limit). */
    val streamBufferSeconds: Int = 120,
    /** Audio: the app's FFmpeg decoders before the device's own (for films that play without sound). */
    val appAudioDecodersFirst: Boolean = false,
    /** Data saver: off, on mobile data, or always (see [dev.yourstream.playback.stream.DataSaver]). */
    val dataSaver: dev.yourstream.playback.stream.DataSaver.Mode = dev.yourstream.playback.stream.DataSaver.Mode.OFF,
) {
    val seekMs: Long get() = seekSeconds * 1000L

    companion object {
        val SEEK_OPTIONS = listOf(5, 10, 15, 30)
        val HOLD_SPEEDS = listOf(1.5f, 2f, 3f)
        val SUBTITLE_SCALES = listOf(0.8f, 1f, 1.25f, 1.5f, 1.8f)
        val BUFFER_OPTIONS = listOf(30, 60, 120, 300)
        /** White, yellow, cyan, green. */
        val SUBTITLE_COLORS = listOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF4DD0E1.toInt(), 0xFF81C784.toInt())
        /** Low, normal (Media3's default), raised, high. */
        val SUBTITLE_OFFSETS = listOf(0.03f, 0.08f, 0.15f, 0.25f)
    }
}

/** The settings, for screens that only read them (player, theme). */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).also(::upgrade)
    private val _settings = MutableStateFlow(load().also(::apply))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        // Data saver "on mobile data" asks the system whenever playback needs to know.
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        dev.yourstream.playback.stream.DataSaver.metered = { connectivity?.isActiveNetworkMetered ?: false }
    }

    /** Settings that live below the UI (network, decoders, data saver) take effect right away. */
    private fun apply(s: AppSettings) {
        dev.yourstream.playback.stream.DataSaver.mode = s.dataSaver
        dev.yourstream.core.network.SecureDns.always = s.secureDns
        dev.yourstream.core.network.SecureDns.fallback = s.secureDnsFallback
        dev.yourstream.ui.core.ImageRouting.useProxies = s.imageProxies
        dev.yourstream.playback.media3.PlayerDecoders.appAudioFirst = s.appAudioDecodersFirst
    }

    /** Settings → Advanced → Reset: every choice back to its default. */
    fun reset() = update { AppSettings() }

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("style", s.style.name)
            .putInt("seek_seconds", s.seekSeconds)
            .putBoolean("autoplay_next", s.autoplayNext)
            .putString("default_fit", s.defaultFit.name)
            .putBoolean("player_landscape", s.playerLandscape)
            .putFloat("hold_speed", s.holdSpeed)
            .putBoolean("pip", s.pictureInPicture)
            .putBoolean("background_playback", s.backgroundPlayback)
            .putFloat("subtitle_scale", s.subtitleScale)
            .putBoolean("subtitle_box", s.subtitleBox)
            .putInt("subtitle_color", s.subtitleColor)
            .putBoolean("subtitle_bold", s.subtitleBold)
            .putFloat("subtitle_offset", s.subtitleOffset)
            .putBoolean("torrents", s.torrentStreaming)
            .putBoolean("secure_dns", s.secureDns)
            .putBoolean("secure_dns_fallback", s.secureDnsFallback)
            .putBoolean("image_proxies", s.imageProxies)
            .putBoolean("amoled", s.amoledBlack)
            .putBoolean("dock_labels", s.dockLabels)
            .putBoolean("reduce_motion", s.reduceMotion)
            .putBoolean("haptics", s.haptics)
            .putInt("poster_width", s.layout.posterWidth)
            .putInt("poster_radius", s.layout.posterRadius ?: -1)
            .putBoolean("hide_labels", s.layout.hideLabels)
            .putBoolean("catalog_type", s.layout.showCatalogType)
            .putBoolean("hide_unreleased", s.layout.hideUnreleased)
            .putString("continue_style", s.layout.continueStyle.name)
            .putString("continue_opens", s.layout.continueOpens.name)
            .putBoolean("size_badges", s.layout.sizeBadges)
            .putBoolean("badges_top", s.layout.badgesOnTop)
            .putBoolean("addon_logo", s.layout.addonLogo)
            .putString("detail_background", s.layout.detailBackground.name)
            .putString("episode_cards", s.layout.episodeCards.name)
            .putBoolean("show_overview", s.layout.showOverview)
            .putBoolean("show_cast", s.layout.showCast)
            .putString("nav_style", s.layout.navStyle.name)
            .putString("stream_sort", s.streamFilter.sort.name)
            .putString("stream_qualities", s.streamFilter.qualities.joinToString(",") { it.name })
            .putBoolean("stream_telegram", s.streamFilter.telegram)
            .putBoolean("stream_addons", s.streamFilter.addons)
            .putBoolean("stream_direct", s.streamFilter.direct)
            .putBoolean("stream_torrents", s.streamFilter.torrents)
            .putString("stream_languages", s.streamFilter.languages.joinToString(","))
            .putBoolean("nav_glow", s.layout.navGlow)
            .putBoolean("home_hero", s.homeRows.hero)
            .putBoolean("home_continue", s.homeRows.continueWatching)
            .putBoolean("home_for_you", s.homeRows.forYou)
            .putBoolean("home_recent", s.homeRows.recentlyAdded)
            .putBoolean("home_series", s.homeRows.series)
            .putBoolean("home_catalogs", s.homeRows.catalogs)
            .putInt("stream_buffer", s.streamBufferSeconds)
            .putBoolean("ffmpeg_first", s.appAudioDecodersFirst)
            .putString("data_saver", s.dataSaver.name)
            .apply()
        apply(s)
        _settings.value = s
    }

    /**
     * Settings files from before 0.11 (no format number): torrents are turned off. Those versions
     * wrote `torrents = true` (then the default) whenever any setting was saved, so the stored
     * value does not mean the user chose torrents; turning them on now goes through the consent
     * dialog (peers see the device's IP address).
     */
    private fun upgrade(p: android.content.SharedPreferences) {
        if (p.getInt(KEY_FORMAT, 0) >= FORMAT) return
        p.edit().putBoolean("torrents", false).putInt(KEY_FORMAT, FORMAT).commit()
    }

    private companion object {
        const val KEY_FORMAT = "settings_format"
        const val FORMAT = 2
    }

    private fun load() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM),
        style = runCatching { UiStyle.valueOf(prefs.getString("style", null)!!) }.getOrDefault(UiStyle.MANGA),
        seekSeconds = prefs.getInt("seek_seconds", 10).takeIf { it in AppSettings.SEEK_OPTIONS } ?: 10,
        autoplayNext = prefs.getBoolean("autoplay_next", true),
        defaultFit = runCatching { VideoFit.valueOf(prefs.getString("default_fit", null)!!) }.getOrDefault(VideoFit.FIT),
        playerLandscape = prefs.getBoolean("player_landscape", true),
        holdSpeed = prefs.getFloat("hold_speed", 2f).takeIf { it in AppSettings.HOLD_SPEEDS } ?: 2f,
        pictureInPicture = prefs.getBoolean("pip", true),
        backgroundPlayback = prefs.getBoolean("background_playback", true),
        subtitleScale = prefs.getFloat("subtitle_scale", 1f).takeIf { it in AppSettings.SUBTITLE_SCALES } ?: 1f,
        subtitleBox = prefs.getBoolean("subtitle_box", false),
        subtitleColor = prefs.getInt("subtitle_color", AppSettings.SUBTITLE_COLORS.first()).takeIf { it in AppSettings.SUBTITLE_COLORS } ?: AppSettings.SUBTITLE_COLORS.first(),
        subtitleBold = prefs.getBoolean("subtitle_bold", false),
        subtitleOffset = prefs.getFloat("subtitle_offset", 0.08f).takeIf { it in AppSettings.SUBTITLE_OFFSETS } ?: 0.08f,
        // Off unless the user turned it on (before 0.11 it defaulted to on without asking).
        torrentStreaming = prefs.getBoolean("torrents", false),
        secureDns = prefs.getBoolean("secure_dns", false),
        secureDnsFallback = prefs.getBoolean("secure_dns_fallback", true),
        imageProxies = prefs.getBoolean("image_proxies", true),
        amoledBlack = prefs.getBoolean("amoled", false),
        dockLabels = prefs.getBoolean("dock_labels", true),
        reduceMotion = prefs.getBoolean("reduce_motion", false),
        haptics = prefs.getBoolean("haptics", true),
        layout = LayoutSettings(
            // Before 0.11.4: small / medium / large posters.
            posterWidth = prefs.getInt("poster_width", when (prefs.getString("poster_size", null)) { "SMALL" -> 104; "LARGE" -> 140; else -> 120 })
                .takeIf { it in LayoutSettings.POSTER_WIDTHS } ?: 120,
            posterRadius = prefs.getInt("poster_radius", -1).takeIf { it in LayoutSettings.POSTER_RADII },
            hideLabels = prefs.getBoolean("hide_labels", false),
            showCatalogType = prefs.getBoolean("catalog_type", false),
            hideUnreleased = prefs.getBoolean("hide_unreleased", false),
            continueStyle = enumOr(prefs.getString("continue_style", null), ContinueStyle.WIDE),
            continueOpens = enumOr(prefs.getString("continue_opens", null), ContinueOpens.PLAY),
            sizeBadges = prefs.getBoolean("size_badges", true),
            badgesOnTop = prefs.getBoolean("badges_top", false),
            addonLogo = prefs.getBoolean("addon_logo", true),
            detailBackground = enumOr(prefs.getString("detail_background", null), DetailBackground.NORMAL),
            episodeCards = enumOr(prefs.getString("episode_cards", null), EpisodeCards.LIST),
            showOverview = prefs.getBoolean("show_overview", true),
            showCast = prefs.getBoolean("show_cast", true),
            // 0.11.4's "Floating" is now "Always expanded".
            navStyle = prefs.getString("nav_style", null).let { if (it == "FLOATING") NavStyle.EXPANDED else enumOr(it, NavStyle.ADAPTIVE) },
            navGlow = prefs.getBoolean("nav_glow", false),
        ),
        homeRows = HomeRowsShown(
            hero = prefs.getBoolean("home_hero", true),
            continueWatching = prefs.getBoolean("home_continue", true),
            forYou = prefs.getBoolean("home_for_you", true),
            recentlyAdded = prefs.getBoolean("home_recent", true),
            series = prefs.getBoolean("home_series", true),
            catalogs = prefs.getBoolean("home_catalogs", true),
        ),
        streamFilter = dev.yourstream.ui.screens.addon.StreamFilter(
            sort = enumOr(prefs.getString("stream_sort", null), dev.yourstream.ui.screens.addon.StreamSort.BEST),
            qualities = prefs.getString("stream_qualities", "").orEmpty().split(',').mapNotNull { n -> dev.yourstream.ui.screens.addon.StreamQuality.entries.firstOrNull { it.name == n } }.toSet(),
            telegram = prefs.getBoolean("stream_telegram", true),
            addons = prefs.getBoolean("stream_addons", true),
            direct = prefs.getBoolean("stream_direct", true),
            torrents = prefs.getBoolean("stream_torrents", true),
            languages = prefs.getString("stream_languages", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
        ),
        streamBufferSeconds = prefs.getInt("stream_buffer", 120).takeIf { it in AppSettings.BUFFER_OPTIONS } ?: 120,
        appAudioDecodersFirst = prefs.getBoolean("ffmpeg_first", false),
        dataSaver = enumOr(prefs.getString("data_saver", null), dev.yourstream.playback.stream.DataSaver.Mode.OFF),
    )
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

@HiltViewModel
class AppPreferencesViewModel @Inject constructor(private val store: AppPreferences) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
    fun update(change: (AppSettings) -> AppSettings) = store.update(change)
    fun reset() = store.reset()
}
