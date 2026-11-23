package dev.yourstream.desktop.update

import dev.yourstream.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Updates for the Windows app, the same way the Android app updates itself: the latest GitHub
 * release is checked; its installer is downloaded only when it is newer, checked against the
 * SHA-256 the release lists (SHA256SUMS-windows.txt, written by the release workflow), and run.
 * A file that does not match is deleted and nothing is installed.
 */
class DesktopUpdates(
    private val installedVersion: String,
    private val dir: File,
    private val logger: Logger,
    private val installer: Installer = WindowsInstaller(),
    private val apiBase: String = "https://api.github.com",
    private val repo: String = REPO,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val done: Long, val total: Long) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String) : State
    }

    data class Release(val version: String, val notes: String, val installerUrl: String, val installerName: String, val size: Long, val checksumsUrl: String?)

    /** Runs the downloaded installer (on Windows: msiexec, then the app restarts). */
    fun interface Installer {
        fun install(file: File)
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Looks for a newer release. Quiet on failure when [quiet] (the check at start). */
    suspend fun check(quiet: Boolean = false): State = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        val next = try {
            val release = latest()
            when {
                release == null -> State.Failed("The latest release has no Windows installer yet.")
                newer(release.version, installedVersion) -> State.Available(release)
                else -> State.UpToDate(installedVersion)
            }
        } catch (e: Exception) {
            logger.w(TAG, "update check failed: ${e::class.simpleName}")
            if (quiet) State.Idle else State.Failed("Could not reach GitHub. Check your connection and try again.")
        }
        _state.value = next
        next
    }

    /** Downloads [release]'s installer and checks it. */
    suspend fun download(release: Release): State = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val sumsUrl = release.checksumsUrl ?: error("This release has no checksums, so the update was not downloaded.")
            val expected = parseChecksums(get(sumsUrl).body().readText())[release.installerName]
                ?: error("This release's checksums do not list the installer, so it was not downloaded.")
            val file = File(dir, release.installerName)
            val part = File(dir, release.installerName + ".part")
            val response = http.send(request(release.installerUrl), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) error("GitHub answered ${response.statusCode()}")
            val total = response.headers().firstValueAsLong("Content-Length").orElse(release.size)
            response.body().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var shown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - shown > (1L shl 20)) { shown = done; _state.value = State.Downloading(release, done, total) }
                    }
                }
            }
            if (!sha256(part).equals(expected, ignoreCase = true)) {
                part.delete()
                error("The downloaded update is damaged or was changed. Nothing was installed. Try again.")
            }
            file.delete()
            if (!part.renameTo(file)) error("Could not save the update.")
            State.Ready(release, file).also { _state.value = it }
        } catch (e: Exception) {
            logger.w(TAG, "update download failed: ${e.message}")
            State.Failed(e.message ?: "The update could not be downloaded.").also { _state.value = it }
        }
    }

    /** Runs the checked installer; the app closes so the installer can replace it. */
    fun install(ready: State.Ready) = installer.install(ready.file)

    private fun request(url: String) = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10))
        .header("User-Agent", "YourStream/$installedVersion (Windows)").header("Accept", "application/vnd.github+json").build()

    private fun get(url: String): HttpResponse<java.io.InputStream> {
        val r = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream())
        if (r.statusCode() !in 200..299) { r.body().close(); error("GitHub answered ${r.statusCode()}") }
        return r
    }

    private fun java.io.InputStream.readText() = use { it.readBytes().toString(Charsets.UTF_8) }

    private fun latest(): Release? {
        val body = json.parseToJsonElement(get("$apiBase/repos/$repo/releases/latest").body().readText()).jsonObject
        val tag = body["tag_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val assets = body["assets"]?.jsonArray?.map { it.jsonObject } ?: return null
        val msi = assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith("-windows-x64.msi") == true } ?: return null
        val sums = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == CHECKSUMS }?.get("browser_download_url")?.jsonPrimitive?.content
        return Release(
            version = tag.removePrefix("v"),
            notes = body["body"]?.jsonPrimitive?.content.orEmpty(),
            installerUrl = msi["browser_download_url"]!!.jsonPrimitive.content,
            installerName = msi["name"]!!.jsonPrimitive.content,
            size = msi["size"]?.jsonPrimitive?.longOrNull ?: -1,
            checksumsUrl = sums,
        )
    }

    companion object {
        private const val TAG = "Updates"
        const val REPO = "Deepakraj1207/yourstream"
        const val CHECKSUMS = "SHA256SUMS-windows.txt"

        /** `sha256sum` output ("<64 hex>  <name>", "*" before the name in binary mode) → name to checksum. */
        fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
            .mapNotNull { line -> Regex("^([0-9a-fA-F]{64})\\s+\\*?(\\S.*?)\\s*$").find(line.trim())?.destructured?.let { (hash, name) -> name to hash.lowercase() } }
            .toMap()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** "0.13.10" > "0.13.9"; the same rule as the Android updater. */
        fun newer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.', '-').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/**
 * Installs over the running copy: Windows Installer upgrades it in place (same upgrade code), with
 * a progress window and no questions (/passive), then the app starts again. The app exits right
 * after starting this, because its files must be free to be replaced.
 */
class WindowsInstaller(private val exit: () -> Unit = { kotlin.system.exitProcess(0) }) : DesktopUpdates.Installer {
    override fun install(file: File) {
        // The installed launcher, as jpackage starts the app (absent when run from a build).
        val app = System.getProperty("jpackage.app-path")
        val relaunch = if (app != null) " && start \"\" \"$app\"" else ""
        ProcessBuilder("cmd", "/c", "start /wait \"\" msiexec /i \"${file.absolutePath}\" /passive$relaunch")
            .inheritIO().start()
        exit()
    }
}
