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
enum class PosterSize(val scale: Float) { SMALL(0.85f), MEDIUM(1f), LARGE(1.2f) }

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
    /**
     * Torrent results from stream add-ons play with the built-in torrent engine. Off until the
     * user agrees (peers see the device's IP address): [TorrentConsentDialog].
     */
    val torrentStreaming: Boolean = false,
    /** Resolve every host through DNS over HTTPS (blocked hosts use it automatically anyway). */
    val secureDns: Boolean = false,
    /** Dark theme on pure black (OLED screens): backgrounds and cards' surroundings turn #000. */
    val amoledBlack: Boolean = false,
    /** The phone's navigation bar shows every tab's name under its icon (else only the selected one's). */
    val dockLabels: Boolean = true,
    /**
     * Less movement: pages cross-fade instead of sliding, loading placeholders and the indexing
     * icon stay still. The system "Remove animations" option has the same effect.
     */
    val reduceMotion: Boolean = false,
    val posterSize: PosterSize = PosterSize.MEDIUM,
    val homeRows: HomeRowsShown = HomeRowsShown(),
    /** Add-on and torrent streams: how far ahead the player buffers (within its memory limit). */
    val streamBufferSeconds: Int = 120,
    /** Audio: the app's FFmpeg decoders before the device's own (for films that play without sound). */
    val appAudioDecodersFirst: Boolean = false,
) {
    val seekMs: Long get() = seekSeconds * 1000L

    companion object {
        val SEEK_OPTIONS = listOf(5, 10, 15, 30)
        val HOLD_SPEEDS = listOf(1.5f, 2f, 3f)
        val SUBTITLE_SCALES = listOf(0.8f, 1f, 1.25f, 1.5f, 1.8f)
        val BUFFER_OPTIONS = listOf(30, 60, 120, 300)
    }
}

/** The settings, for screens that only read them (player, theme). */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load().also(::apply))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Settings that live below the UI (network, decoders) take effect right away. */
    private fun apply(s: AppSettings) {
        dev.yourstream.core.network.SecureDns.always = s.secureDns
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
            .putBoolean("torrents", s.torrentStreaming)
            .putBoolean("secure_dns", s.secureDns)
            .putBoolean("amoled", s.amoledBlack)
            .putBoolean("dock_labels", s.dockLabels)
            .putBoolean("reduce_motion", s.reduceMotion)
            .putString("poster_size", s.posterSize.name)
            .putBoolean("home_hero", s.homeRows.hero)
            .putBoolean("home_continue", s.homeRows.continueWatching)
            .putBoolean("home_for_you", s.homeRows.forYou)
            .putBoolean("home_recent", s.homeRows.recentlyAdded)
            .putBoolean("home_series", s.homeRows.series)
            .putBoolean("home_catalogs", s.homeRows.catalogs)
            .putInt("stream_buffer", s.streamBufferSeconds)
            .putBoolean("ffmpeg_first", s.appAudioDecodersFirst)
            .apply()
        apply(s)
        _settings.value = s
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
        // Off unless the user turned it on (before 0.11 it defaulted to on without asking).
        torrentStreaming = prefs.getBoolean("torrents", false),
        secureDns = prefs.getBoolean("secure_dns", false),
        amoledBlack = prefs.getBoolean("amoled", false),
        dockLabels = prefs.getBoolean("dock_labels", true),
        reduceMotion = prefs.getBoolean("reduce_motion", false),
        posterSize = runCatching { PosterSize.valueOf(prefs.getString("poster_size", null)!!) }.getOrDefault(PosterSize.MEDIUM),
        homeRows = HomeRowsShown(
            hero = prefs.getBoolean("home_hero", true),
            continueWatching = prefs.getBoolean("home_continue", true),
            forYou = prefs.getBoolean("home_for_you", true),
            recentlyAdded = prefs.getBoolean("home_recent", true),
            series = prefs.getBoolean("home_series", true),
            catalogs = prefs.getBoolean("home_catalogs", true),
        ),
        streamBufferSeconds = prefs.getInt("stream_buffer", 120).takeIf { it in AppSettings.BUFFER_OPTIONS } ?: 120,
        appAudioDecodersFirst = prefs.getBoolean("ffmpeg_first", false),
    )
}

@HiltViewModel
class AppPreferencesViewModel @Inject constructor(private val store: AppPreferences) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
    fun update(change: (AppSettings) -> AppSettings) = store.update(change)
    fun reset() = store.reset()
}
