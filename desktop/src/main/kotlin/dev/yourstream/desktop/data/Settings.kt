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
    /** Where playback stopped, per Telegram remote file id, in milliseconds. */
    val positions: Map<String, Long> = emptyMap(),
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

    @Synchronized
    fun update(change: (DesktopSettings) -> DesktopSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(DesktopSettings.serializer(), next))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
