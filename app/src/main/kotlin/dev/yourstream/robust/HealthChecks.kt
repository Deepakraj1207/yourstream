package dev.yourstream.robust

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.yourstream.catalog.core.AvailabilityState
import dev.yourstream.catalog.core.CatalogManager
import dev.yourstream.core.database.DatabaseIntegrity
import dev.yourstream.core.database.DatabaseOpenReport
import dev.yourstream.core.database.DatabaseOpener
import dev.yourstream.core.database.OpenedDatabase
import dev.yourstream.core.diagnostics.CheckOutcome
import dev.yourstream.core.diagnostics.DiagnosticCategory
import dev.yourstream.core.diagnostics.HealthCheck
import dev.yourstream.core.diagnostics.failed
import dev.yourstream.core.diagnostics.ok
import dev.yourstream.core.diagnostics.warning
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.ProviderState
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.api.model.TelegramStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MB = 1_048_576L

/** Opens, integrity, counts. Runs off the main thread. */
class DatabaseCheck(private val context: Context, private val opened: () -> OpenedDatabase) : HealthCheck {
    override val name = "database"
    override val category = DiagnosticCategory.DATABASE
    override suspend fun check(): CheckOutcome = withContext(Dispatchers.IO) {
        val o = opened()
        val r = DatabaseIntegrity.check(o.db.openHelper.writableDatabase, o.file)
        val details = arrayOf(
            "opened: ${o.report.outcome} (on disk v${o.report.previousVersion ?: "-"} → v${o.report.currentVersion}, ${o.report.tookMs} ms)",
            "quick_check: ${r.quickCheck}; foreign-key violations: ${r.foreignKeyViolations}; broken FTS: ${r.brokenFts.ifEmpty { listOf("none") }.joinToString()}",
            "unreadable cache rows: ${r.invalidCacheRows}; corrupted metadata rows: ${r.invalidMetadataRows}",
            "size ${r.sizeBytes / MB} MB; " + r.counts.entries.joinToString { "${it.key} ${it.value}" },
            "backups: ${DatabaseOpener.backups(context).size}; quarantined: ${DatabaseOpener.quarantined(context).size}",
        ) + listOfNotNull(o.report.error?.let { "open error: $it" }, o.report.salvaged.takeIf { it.isNotEmpty() }?.let { "salvaged: $it" })
        when {
            !r.quickCheck.equals("ok") -> failed("integrity check failed: ${r.quickCheck.take(120)}", *details)
            o.report.outcome == DatabaseOpenReport.Outcome.RECREATED -> warning("database was recreated empty at startup (old file kept)", *details)
            o.report.outcome == DatabaseOpenReport.Outcome.SALVAGED -> warning("database was salvaged at startup", *details)
            !r.ok -> warning("repairable problems found (use \"Repair database\")", *details)
            else -> ok("v${r.version}, ${r.counts["library_item"]} items, ${r.counts["media"]} files", *details)
        }
    }
}

class TelegramCheck(private val telegram: TelegramManager, private val safeMode: Boolean) : HealthCheck {
    override val name = "telegram"
    override val category = DiagnosticCategory.TELEGRAM
    override suspend fun check(): CheckOutcome {
        val status = telegram.status.value
        val conn = telegram.connectionState.value
        val detail = "status $status, connection $conn"
        return when {
            safeMode && status == TelegramStatus.Stopped -> warning("not started (safe mode); the local library still works", detail)
            status is TelegramStatus.NotConfigured -> warning("not configured: ${status.reason} (offline library still works)", detail)
            status is TelegramStatus.Failed -> failed("TDLib failed: ${status.error.message}", detail)
            status != TelegramStatus.Running -> warning("TDLib $status", detail)
            conn == TelegramConnectionState.READY -> ok("connected", detail)
            conn == TelegramConnectionState.WAITING_FOR_NETWORK -> warning("waiting for network (library and downloaded files still available)", detail)
            else -> warning("connecting ($conn)", detail)
        }
    }
}

class NetworkCheck(private val context: Context) : HealthCheck {
    override val name = "network"
    override val category = DiagnosticCategory.NETWORK
    override suspend fun check(): CheckOutcome {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return warning("no connectivity service")
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return warning("offline: browsing and downloaded content only")
        val transport = listOf(NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi", NetworkCapabilities.TRANSPORT_CELLULAR to "mobile", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "VPN")
            .filter { caps.hasTransport(it.first) }.joinToString { it.second }.ifEmpty { "other" }
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val detail = "$transport, ${if (metered) "metered" else "unmetered"}, down ${caps.linkDownstreamBandwidthKbps} kbit/s"
        return if (validated) ok("online ($transport)", detail) else warning("connected but no internet access ($transport)", detail)
    }
}

class MetadataProvidersCheck(private val manager: () -> MetadataProviderManager) : HealthCheck {
    override val name = "metadata providers"
    override val category = DiagnosticCategory.METADATA
    override suspend fun check(): CheckOutcome {
        val s = manager().status()
        if (s.isEmpty()) return warning("no metadata provider configured (titles stay as parsed from file names)")
        val details = s.map { "${it.provider.value}: ${it.state}, ${it.consecutiveFailures} failures${it.lastError?.let { e -> ", last error: $e" } ?: ""}" }.toTypedArray()
        val down = s.count { it.state != ProviderState.AVAILABLE }
        return when (down) {
            0 -> ok("${s.size} available", *details)
            s.size -> failed("all providers unavailable; cached metadata is used", *details)
            else -> warning("$down of ${s.size} unavailable; falling back to the others", *details)
        }
    }
}

class CatalogProvidersCheck(private val manager: () -> CatalogManager) : HealthCheck {
    override val name = "catalog providers"
    override val category = DiagnosticCategory.CATALOG
    override suspend fun check(): CheckOutcome {
        val a = manager().availability()
        if (a.isEmpty()) return ok("none configured")
        val details = a.map { "${it.displayName}: ${it.state}${it.error?.let { e -> " ($e)" } ?: ""}" }.toTypedArray()
        val bad = a.count { it.state == AvailabilityState.UNAVAILABLE || it.state == AvailabilityState.PAUSED }
        return if (bad == 0) ok("${a.size} providers", *details) else warning("$bad of ${a.size} unavailable (cached catalogs served)", *details)
    }
}

class BackgroundWorkCheck(private val context: Context) : HealthCheck {
    override val name = "background work"
    override val category = DiagnosticCategory.WORK
    override suspend fun check(): CheckOutcome = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        val infos = buildList {
            addAll(wm.getWorkInfosByTag("media-index").get())
            for (n in listOf("media-index-periodic", "metadata-sync", MaintenanceWorker.NAME)) addAll(wm.getWorkInfosForUniqueWork(n).get())
        }.distinctBy { it.id }
        val byState = infos.groupingBy { it.state }.eachCount()
        val failedCount = byState[WorkInfo.State.FAILED] ?: 0
        val details = infos.filter { !it.state.isFinished || it.state == WorkInfo.State.FAILED }.take(15)
            .map { "${it.tags.firstOrNull { t -> !t.contains('.') } ?: it.tags.firstOrNull() ?: it.id}: ${it.state}, attempt ${it.runAttemptCount}" }.toTypedArray()
        val summary = byState.entries.joinToString { "${it.key.name.lowercase()} ${it.value}" }.ifEmpty { "nothing scheduled" }
        if (failedCount > 0) warning("$failedCount jobs failed; $summary", *details) else ok(summary, *details)
    }
}

class MemoryCheck(private val context: Context, private val diagnostics: AppDiagnostics) : HealthCheck {
    override val name = "memory"
    override val category = DiagnosticCategory.MEMORY
    override suspend fun check(): CheckOutcome {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val detail = "heap ${used / MB} / ${rt.maxMemory() / MB} MB (class ${am?.memoryClass} MB); device available ${mi.availMem / MB} MB of ${mi.totalMem / MB} MB; " +
            "native ${android.os.Debug.getNativeHeapAllocatedSize() / MB} MB; low-RAM device ${diagnostics.lowRamDevice}; last trim level ${diagnostics.lastTrimLevel ?: "-"}"
        return when {
            used > rt.maxMemory() * 0.85 -> warning("heap almost full (${used / MB} MB)", detail)
            mi.lowMemory -> warning("device is low on memory", detail)
            else -> ok("heap ${used / MB} MB of ${rt.maxMemory() / MB} MB", detail)
        }
    }
}

class StorageCheck(private val context: Context) : HealthCheck {
    override val name = "storage"
    override val category = DiagnosticCategory.STORAGE
    override suspend fun check(): CheckOutcome = withContext(Dispatchers.IO) {
        val free = context.filesDir.usableSpace
        val tdlibFiles = dirSize(File(context.cacheDir, "tdlib/files"))
        val detail = "free ${free / MB} MB; Telegram file cache ${tdlibFiles / MB} MB; database ${context.getDatabasePath("yourstream.db").length() / MB} MB; " +
            "diagnostics ${dirSize(File(context.filesDir, "diagnostics")) / 1024} KB"
        when {
            free < 200 * MB -> failed("only ${free / MB} MB free: downloads and the database may fail", detail)
            free < 1024 * MB -> warning("${free / MB} MB free", detail)
            else -> ok("${free / MB} MB free", detail)
        }
    }

    private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

class PowerCheck(private val context: Context) : HealthCheck {
    override val name = "battery"
    override val category = DiagnosticCategory.WORK
    override suspend fun check(): CheckOutcome {
        val pm = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { b -> b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
        val saver = pm?.isPowerSaveMode == true
        val unrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val detail = "battery ${level ?: "?"}%${if (charging) " charging" else ""}; battery saver $saver; doze ${pm?.isDeviceIdleMode}; exempt from optimization $unrestricted"
        return if (saver) warning("battery saver on: background indexing and metadata sync wait", detail) else ok("battery ${level ?: "?"}%", detail)
    }
}

class CrashCheck(private val diagnostics: AppDiagnostics) : HealthCheck {
    override val name = "crashes"
    override val category = DiagnosticCategory.CRASH
    override suspend fun check(): CheckOutcome {
        val list = diagnostics.crashes.list()
        val details = list.take(5).map { "${java.util.Date(it.time)}: ${it.summary}" }.toTypedArray()
        return when {
            diagnostics.safeMode -> warning("safe mode (${diagnostics.startup.earlyDeaths} early exits, ${diagnostics.startup.recentCrashes} recent crashes)", *details)
            list.isEmpty() -> ok("no crash reports")
            else -> warning("${list.size} crash reports", *details)
        }
    }
}

class LoggingCheck(private val diagnostics: AppDiagnostics) : HealthCheck {
    override val name = "logging"
    override val category = DiagnosticCategory.OTHER
    override suspend fun check(): CheckOutcome {
        val detail = "log ${diagnostics.log.sizeBytes() / 1024} KB in ${diagnostics.log.files().size} files; write failures ${diagnostics.log.failures}; " +
            "errors this run ${diagnostics.events.errorCount()}; problems by area ${diagnostics.events.problemCounts()}"
        return if (diagnostics.log.failures > 0) warning("log writes failing", detail) else ok("persistent log active", detail)
    }
}
