package dev.yourstream.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopSettings(
    /** TMDB API key (v3) or read token (v4) for posters; Cinemeta is used without one. */
    val tmdbKey: String = "",
    val darkTheme: Boolean = true,
    /** The manga / comic look (the Android app's default); false = classic. */
    val mangaStyle: Boolean = true,
    /** Where playback stopped, per Telegram remote file id, in milliseconds. */
    val positions: Map<String, Long> = emptyMap(),
    /** The audio language last picked in the player (ISO 639-1), tried first next time. */
    val audioLanguage: String? = null,
    /** The subtitle language last picked; null = subtitles off. */
    val subtitleLanguage: String? = null,
    val volume: Int = 100,
)

/** settings.json in the data folder. Written whole (to a temporary file, then renamed). */
class SettingsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()

    private fun load(): DesktopSettings = try {
        if (file.isFile) json.decodeFromString(DesktopSettings.serializer(), file.readText()) else DesktopSettings()
    } catch (_: Exception) {
        DesktopSettings()
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "settings").apply { isDaemon = true } }

    /** Changes the settings at once; the file is written on a background thread (latest wins). */
    @Synchronized
    fun update(change: (DesktopSettings) -> DesktopSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        writer.execute { write() }
    }

    private val fileLock = Any()

    private fun write() = synchronized(fileLock) {
        val current = _settings.value
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(DesktopSettings.serializer(), current))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Writes anything pending now (at exit). */
    fun flush() {
        writer.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}
