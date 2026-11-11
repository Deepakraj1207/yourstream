package dev.yourstream.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
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
 * Updates from the repository's GitHub Releases, installed inside the app (like Nuvio): on start
 * the latest release is compared with the installed version; "Update" downloads its APK with
 * progress and hands it to Android's package installer, which shows its own "Update this app?"
 * confirmation over the app. No browser, no Releases page. Android accepts it only when it is
 * signed with the same key as the installed app (CI's release key).
 */
@Singleton
class GitHubAppUpdates @Inject constructor(@ApplicationContext private val context: Context) : AppUpdates {
    override val installedVersion: String = BuildConfig.VERSION_NAME
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val lock = Mutex()
    private var release: Release? = null

    override suspend fun check(quiet: Boolean) = lock.withLock {
        if (BuildConfig.UPDATE_REPO.isBlank()) return@withLock
        // A download or install in progress is not interrupted by another check.
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return@withLock
        if (!quiet) _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { latestRelease() } }
        _state.value = result.fold(
            onSuccess = { r ->
                release = r
                if (r != null && newer(r.version, installedVersion)) UpdateState.Available(r.version, r.notes, r.size)
                else UpdateState.UpToDate(installedVersion)
            },
            onFailure = { if (quiet) UpdateState.Idle else UpdateState.Failed(it.message ?: "No connection") },
        )
    }

    override suspend fun downloadAndInstall() {
        val r = release ?: return
        val file = apkFile(r.version)
        if (!(file.exists() && (r.size <= 0 || file.length() == r.size))) {
            _state.value = UpdateState.Downloading(r.version, 0f)
            val ok = withContext(Dispatchers.IO) { runCatching { download(r, file) } }
            if (ok.isFailure) { _state.value = UpdateState.Failed(ok.exceptionOrNull()?.message ?: "Download failed"); return }
        }
        install(r.version, file)
    }

    /** Back in the app after allowing "Install unknown apps": continue the install by itself. */
    override suspend fun onAppResumed() {
        val waiting = _state.value as? UpdateState.NeedsPermission ?: return
        if (canInstall()) install(waiting.version, apkFile(waiting.version))
    }

    /** Called by [UpdateInstallReceiver] with the installer's verdict. */
    internal fun onInstallResult(status: Int, message: String?) {
        val version = (state.value as? UpdateState.Installing)?.version ?: release?.version ?: return
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate(version)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(version, release?.notes.orEmpty(), release?.size ?: 0)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("This update is signed differently from the installed app. Uninstall YourStream once, then install the new version.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Not enough storage to install the update.")
            else -> UpdateState.Failed(message ?: "The update was not installed.")
        }
    }

    private fun install(version: String, file: File) {
        if (!file.exists()) { _state.value = UpdateState.Failed("The downloaded update is missing. Try again."); return }
        if (!canInstall()) {
            // Android 8+: the user allows installs from YourStream once; we continue on return.
            _state.value = UpdateState.NeedsPermission(version)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        _state.value = UpdateState.Installing(version)
        runCatching { installWithSession(file) }.onFailure {
            // Some devices refuse sessions from apps; the classic installer screen still works.
            runCatching { installWithIntent(file) }.onFailure { e -> _state.value = UpdateState.Failed(e.message ?: "Could not start the installer") }
        }
    }

    /** PackageInstaller session: Android shows its update confirmation right over the app. */
    private fun installWithSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName), flags)
            session.commit(pending.intentSender)
        }
    }

    private fun installWithIntent(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun canInstall() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun download(r: Release, file: File) {
        file.parentFile?.listFiles()?.forEach { if (it != file) it.delete() } // older downloads
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        val conn = connect(r.apkUrl)
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    if (percent != shown) { shown = percent; _state.value = UpdateState.Downloading(r.version, if (total > 0) done.toFloat() / total else null) }
                }
            }
        }
        if (total > 0 && part.length() != total) { part.delete(); error("The download was interrupted. Try again.") }
        if (!part.renameTo(file)) error("Could not save the update")
    }

    private fun apkFile(version: String) = File(File(context.cacheDir, "updates"), "YourStream-$version.apk")

    private data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private fun latestRelease(): Release? {
        val conn = connect("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest", "application/vnd.github+json")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name").ifBlank { return null }
        val assets = json.optJSONArray("assets") ?: return null
        val list = (0 until assets.length()).map { assets.getJSONObject(it) }
        val i = pickApk(list.map { it.optString("name") }, Build.SUPPORTED_ABIS.toList()) ?: return null
        val a = list[i]
        return Release(tag.removePrefix("v"), json.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
    }

    /** Opens [url] with every header set before connecting (setting one afterwards throws). */
    private fun connect(url: String, accept: String? = null): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "YourStream/$installedVersion (Android)")
            accept?.let { setRequestProperty("Accept", it) }
        }
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); throw IllegalStateException("GitHub answered $code") }
        return conn
    }

    companion object {
        private val ABI_SUFFIX = Regex("-(arm64-v8a|armeabi-v7a|x86_64|x86)\\.apk$")

        /**
         * Which release asset to install: the APK built for the device's preferred CPU type
         * ("YourStream-v0.8.0-arm64-v8a.apk"), else the universal APK, else any APK (pure; tested).
         */
        fun pickApk(names: List<String>, abis: List<String>): Int? {
            for (abi in abis) names.indexOfFirst { it.endsWith("-$abi.apk") }.takeIf { it >= 0 }?.let { return it }
            names.indexOfFirst { it.endsWith(".apk") && !ABI_SUFFIX.containsMatchIn(it) }.takeIf { it >= 0 }?.let { return it }
            return names.indexOfFirst { it.endsWith(".apk") }.takeIf { it >= 0 }
        }

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

/** The package installer's answer for an update session (confirmation needed, done, failed). */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updates: GitHubAppUpdates

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            else -> updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds abstract fun updates(impl: GitHubAppUpdates): AppUpdates
}
