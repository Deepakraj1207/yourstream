package dev.yourstream.desktop.playback

import dev.yourstream.core.common.Logger
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryProviderPriority
import java.io.File

/**
 * Finds VLC's libraries (libvlc). Videos play through VLC because it decodes everything people
 * keep in Telegram (MKV, HEVC, AV1, 4K, HDR, AC-3/DTS audio, soft subtitles), like the FFmpeg
 * decoders do on Android.
 *
 * Looked for, in order: a copy shipped inside the installer (`vlc` in the app's resources), then
 * an installed VLC (vlcj looks in the usual places: Program Files and the registry on Windows,
 * /Applications on macOS, the system library path on Linux).
 */
object Vlc {
    @Volatile private var found: Boolean? = null

    fun available(logger: Logger): Boolean = found ?: synchronized(this) {
        found ?: discover(logger).also { found = it }
    }

    /** Asks again (after the user installed VLC). */
    fun retry(logger: Logger): Boolean = synchronized(this) { discover(logger).also { found = it } }

    private fun discover(logger: Logger): Boolean {
        // The bundled copy is offered to vlcj first by BundledVlcDirectory (registered in
        // META-INF/services); vlcj then also points VLC at its plugins folder.
        return try {
            NativeDiscovery().discover().also { logger.i("Vlc", if (it) "VLC found" else "VLC not found") }
        } catch (t: Throwable) {
            logger.w("Vlc", "looking for VLC failed", t)
            false
        }
    }

    /** VLC shipped with the app (Compose's packaging puts app resources in this folder). */
    internal fun bundledDir(): File? = System.getProperty("compose.application.resources.dir")
        ?.let { File(it, "vlc") }?.takeIf { File(it, if (isWindows) "libvlc.dll" else "libvlc.so").isFile }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    const val DOWNLOAD_URL = "https://www.videolan.org/vlc/"
}

/** Tells vlcj's discovery about the VLC copy inside the installer, ahead of an installed VLC. */
class BundledVlcDirectory : DiscoveryDirectoryProvider {
    override fun priority(): Int = DiscoveryProviderPriority.CONFIG_FILE + 1
    override fun directories(): Array<String> = listOfNotNull(Vlc.bundledDir()?.absolutePath).toTypedArray()
    override fun supported(): Boolean = Vlc.bundledDir() != null
}
