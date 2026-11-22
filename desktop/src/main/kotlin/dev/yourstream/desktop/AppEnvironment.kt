package dev.yourstream.desktop

import dev.yourstream.core.common.Logger
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** Where the app keeps its data, per operating system. Nothing is written next to the program. */
object AppPaths {
    val os: String = System.getProperty("os.name").orEmpty()
    val isWindows: Boolean = os.startsWith("Windows", ignoreCase = true)
    val isMac: Boolean = os.startsWith("Mac", ignoreCase = true)

    /**
     * Windows: %LOCALAPPDATA%\YourStream (the Telegram session and cache stay on this PC and are
     * not copied by roaming profiles). macOS: ~/Library/Application Support/YourStream.
     * Linux: $XDG_DATA_HOME/yourstream or ~/.local/share/yourstream.
     */
    fun dataDir(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): File = when {
        isWindows -> File(env["LOCALAPPDATA"] ?: "$home\\AppData\\Local", "YourStream")
        isMac -> File(home, "Library/Application Support/YourStream")
        else -> File(env["XDG_DATA_HOME"] ?: "$home/.local/share", "yourstream")
    }
}

/** Values fixed at build time (yourstream-build.properties, written by the Gradle build). */
data class BuildInfo(val apiId: Int, val apiHash: String, val version: String) {
    val hasTelegramCredentials: Boolean get() = apiId != 0 && apiHash.isNotBlank()

    companion object {
        fun load(): BuildInfo {
            val p = Properties()
            BuildInfo::class.java.getResourceAsStream("/yourstream-build.properties")?.use { p.load(it) }
            return BuildInfo(
                apiId = p.getProperty("telegram.apiId")?.toIntOrNull() ?: 0,
                apiHash = p.getProperty("telegram.apiHash").orEmpty(),
                version = p.getProperty("version") ?: "dev",
            )
        }
    }
}

/**
 * Writes the app's log to `logs/yourstream.log` in the data folder (kept under 2 MB, one older
 * file). Messages are the app's own; phone numbers, codes and passwords are never passed to it.
 */
class FileLogger(dir: File, private val maxBytes: Long = 2L * 1024 * 1024) : Logger {
    private val file = File(dir, "yourstream.log")
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    init { dir.mkdirs() }

    @Synchronized
    private fun write(level: Char, tag: String, message: String, t: Throwable?) {
        try {
            if (file.length() > maxBytes) file.renameTo(File(file.parentFile, "yourstream.1.log"))
            PrintWriter(java.io.FileOutputStream(file, true).writer(Charsets.UTF_8)).use { w ->
                w.println("${LocalDateTime.now().format(time)} $level/$tag: $message")
                t?.printStackTrace(w)
            }
        } catch (_: Exception) {
            // Logging must never take the app down.
        }
    }

    override fun d(tag: String, message: String) = write('D', tag, message, null)
    override fun i(tag: String, message: String) = write('I', tag, message, null)
    override fun w(tag: String, message: String, throwable: Throwable?) = write('W', tag, message, throwable)
    override fun e(tag: String, message: String, throwable: Throwable?) = write('E', tag, message, throwable)
}
