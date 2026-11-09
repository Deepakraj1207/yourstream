package dev.yourstream.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.BuildConfig
import dev.yourstream.ui.screens.update.AppUpdates
import dev.yourstream.ui.screens.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Updates from the repository's GitHub Releases (the same place the APK is published): the latest
 * release's tag is compared with the installed version; its `.apk` asset is downloaded into the
 * app's cache and handed to the system installer. Android installs it over this app only when it
 * is signed with the same key (CI's release key).
 */
@Singleton
class GitHubAppUpdates @Inject constructor(@ApplicationContext private val context: Context) : AppUpdates {
    override val installedVersion: String = BuildConfig.VERSION_NAME
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var apkUrl: String? = null

    override suspend fun check(quiet: Boolean) = lock.withLock {
        val now = System.currentTimeMillis()
        if (quiet && now - prefs.getLong(KEY_LAST_CHECK, 0) < QUIET_INTERVAL_MS && _state.value !is UpdateState.Idle) return@withLock
        if (BuildConfig.UPDATE_REPO.isBlank()) return@withLock
        if (!quiet) _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { latestRelease() } }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        _state.value = result.fold(
            onSuccess = { r ->
                if (r != null && newer(r.version, installedVersion)) { apkUrl = r.apkUrl; UpdateState.Available(r.version, r.notes, r.size) }
                else UpdateState.UpToDate(installedVersion)
            },
            onFailure = { if (quiet) UpdateState.Idle else UpdateState.Failed(it.message ?: "No connection") },
        )
    }

    override suspend fun downloadAndInstall() {
        val available = _state.value as? UpdateState.Available ?: (_state.value as? UpdateState.Ready)?.let { return openInstaller(apkFile(it.version)) } ?: return
        val url = apkUrl ?: return
        val file = apkFile(available.version)
        _state.value = UpdateState.Downloading(available.version, 0f)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.listFiles()?.forEach { it.delete() } // older downloads
                file.parentFile?.mkdirs()
                val conn = open(url)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: available.sizeBytes
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastShown = 0f
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val f = if (total > 0) done.toFloat() / total else null
                            if (f == null || f - lastShown >= 0.01f) { lastShown = f ?: 0f; _state.value = UpdateState.Downloading(available.version, f) }
                        }
                    }
                }
            }
        }
        if (ok.isFailure) { _state.value = UpdateState.Failed(ok.exceptionOrNull()?.message ?: "Download failed"); return }
        _state.value = UpdateState.Ready(available.version)
        openInstaller(file)
    }

    private fun openInstaller(file: File) {
        if (!file.exists()) return
        // Android 8+: the user allows "Install unknown apps" for this app once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun apkFile(version: String) = File(File(context.cacheDir, "updates"), "YourStream-$version.apk")

    private data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private fun latestRelease(): Release? {
        val conn = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name").ifBlank { return null }
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(tag.removePrefix("v"), json.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "YourStream/$installedVersion")
        if (responseCode !in 200..299) throw IllegalStateException("Server answered $responseCode")
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check"
        private const val QUIET_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** "0.10.0" > "0.9.2"; non-numeric parts are ignored (pure). */
        fun newer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.', '-').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds abstract fun updates(impl: GitHubAppUpdates): AppUpdates
}
