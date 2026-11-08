package dev.yourstream.robust

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dev.yourstream.BuildConfig
import dev.yourstream.core.common.Clock
import dev.yourstream.core.diagnostics.CrashStore
import dev.yourstream.core.diagnostics.DiagnosticCategory
import dev.yourstream.core.diagnostics.EventJournal
import dev.yourstream.core.diagnostics.PlaybackSessionStore
import dev.yourstream.core.diagnostics.RotatingFileLog
import dev.yourstream.core.diagnostics.StartupGuard
import java.io.File

/**
 * Diagnostics that exist before anything else (before Hilt, the database or Telegram), so even
 * a crash during startup is recorded. Files live in app-private `files/diagnostics/`; everything
 * written there is redacted.
 */
class AppDiagnostics private constructor(context: Context) {
    val dir = File(context.filesDir, "diagnostics")
    val clock: Clock = Clock.System
    val log = RotatingFileLog(File(dir, "logs"), "app", maxBytes = 512 * 1024, maxFiles = 3)
    val events = EventJournal(clock, RotatingFileLog(File(dir, "logs"), "events", maxBytes = 256 * 1024, maxFiles = 2))
    val crashes = CrashStore(File(dir, "crashes"), clock)
    val guard = StartupGuard(dir, clock)
    val playbackSession = PlaybackSessionStore(File(dir, "playback-session.properties"))
    val startup: StartupGuard.Startup = guard.begin(crashes.list())
    val safeMode: Boolean get() = startup.safeMode
    val lowRamDevice: Boolean = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    @Volatile var lastTrimLevel: Int? = null

    fun deviceInfo(): Map<String, String> = linkedMapOf(
        "app" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (BuildConfig.DEBUG) "debug" else "release"}",
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "abi" to Build.SUPPORTED_ABIS.joinToString(),
        "lowRam" to lowRamDevice.toString(),
        "safeMode" to "${startup.safeMode}${if (startup.forcedSafeMode) " (forced)" else ""}",
        "earlyDeaths" to startup.earlyDeaths.toString(),
        "recentCrashes" to startup.recentCrashes.toString(),
    )

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Everything here is best effort and synchronous: the process dies right after.
            runCatching {
                events.error(DiagnosticCategory.CRASH, "Uncaught exception on ${thread.name}", error)
                val rt = Runtime.getRuntime()
                crashes.write(
                    thread.name, error,
                    deviceInfo() + mapOf("heap" to "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} / ${rt.maxMemory() / 1_048_576} MB"),
                    log.tail(80),
                )
                log.appendNow("CRASH ${thread.name}: ${EventJournal.describe(error)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        @Volatile private var instance: AppDiagnostics? = null

        /** Called first thing in Application.onCreate. */
        fun init(context: Context): AppDiagnostics = instance ?: synchronized(this) {
            instance ?: AppDiagnostics(context.applicationContext).also {
                instance = it
                it.installCrashHandler()
                if (it.safeMode) it.events.warn(DiagnosticCategory.STARTUP, "Safe mode: ${it.startup}")
            }
        }

        fun get(context: Context): AppDiagnostics = init(context)
    }
}
