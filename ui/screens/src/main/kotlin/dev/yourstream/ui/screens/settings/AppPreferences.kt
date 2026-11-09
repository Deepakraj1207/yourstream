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
    /** Subtitle text size relative to the player's default. */
    val subtitleScale: Float = 1f,
    /** Subtitles on a dark box (else white text with a black outline). */
    val subtitleBox: Boolean = false,
    /** Torrent results from stream add-ons play with the built-in torrent engine. */
    val torrentStreaming: Boolean = true,
) {
    val seekMs: Long get() = seekSeconds * 1000L

    companion object {
        val SEEK_OPTIONS = listOf(5, 10, 15, 30)
        val HOLD_SPEEDS = listOf(1.5f, 2f, 3f)
        val SUBTITLE_SCALES = listOf(0.8f, 1f, 1.25f, 1.5f, 1.8f)
    }
}

/** The settings, for screens that only read them (player, theme). */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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
            .putFloat("subtitle_scale", s.subtitleScale)
            .putBoolean("subtitle_box", s.subtitleBox)
            .putBoolean("torrents", s.torrentStreaming)
            .apply()
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
        subtitleScale = prefs.getFloat("subtitle_scale", 1f).takeIf { it in AppSettings.SUBTITLE_SCALES } ?: 1f,
        subtitleBox = prefs.getBoolean("subtitle_box", false),
        torrentStreaming = prefs.getBoolean("torrents", true),
    )
}

@HiltViewModel
class AppPreferencesViewModel @Inject constructor(private val store: AppPreferences) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
    fun update(change: (AppSettings) -> AppSettings) = store.update(change)
}
