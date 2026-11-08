package dev.yourstream

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration as AndroidConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.MemoryPressure
import dev.yourstream.core.database.DatabaseOpenReport
import dev.yourstream.core.database.OpenedDatabase
import dev.yourstream.core.diagnostics.DiagnosticCategory
import dev.yourstream.di.ApplicationScope
import dev.yourstream.media.indexer.IndexerInitializer
import dev.yourstream.robust.AppDiagnostics
import dev.yourstream.robust.MaintenanceWorker
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.api.model.TelegramStatus
import dev.yourstream.telegram.tdlib.TelegramInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YourStreamApp : Application(), Configuration.Provider {

    @Inject lateinit var telegram: TelegramInitializer
    @Inject lateinit var telegramManager: TelegramManager
    @Inject lateinit var indexer: IndexerInitializer
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var database: dagger.Lazy<OpenedDatabase>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private lateinit var diagnostics: AppDiagnostics

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // Before Hilt: a crash while building the object graph is still recorded.
        diagnostics = AppDiagnostics.init(this)
        super.onCreate()
        registerComponentCallbacks(memoryCallbacks)
        appScope.launch { startup() }
    }

    /**
     * Each step is isolated: Telegram failing to start, or any other step throwing, never stops
     * the others or the app. In safe mode (crash loop detected) only the local database is opened.
     */
    private suspend fun startup() {
        val journal = diagnostics.events
        journal.info(DiagnosticCategory.STARTUP, "Process start" + if (diagnostics.safeMode) " in SAFE MODE" else "")
        step("database") {
            // Opened here, off the main thread, so a migration never runs on the UI thread.
            val r = database.get().report
            val message = "Database ${r.outcome.name.lowercase()} (v${r.previousVersion ?: "-"} → v${r.currentVersion}, ${r.tookMs} ms)" +
                (r.error?.let { ": $it" } ?: "") + (r.salvaged.takeIf { it.isNotEmpty() }?.let { " — recovered ${it.values.sum()} rows" } ?: "")
            when (r.outcome) {
                DatabaseOpenReport.Outcome.SALVAGED, DatabaseOpenReport.Outcome.RECREATED -> journal.error(DiagnosticCategory.DATABASE, message)
                DatabaseOpenReport.Outcome.MIGRATED -> journal.warn(DiagnosticCategory.DATABASE, message)
                else -> journal.info(DiagnosticCategory.DATABASE, message)
            }
        }
        if (!diagnostics.safeMode) {
            step("telegram") {
                // Starts TDLib; a saved session resumes without a new login. Failure = offline mode.
                val r = telegram.start()
                if (r is AppResult.Err) journal.warn(DiagnosticCategory.TELEGRAM, "Telegram not started: ${r.error.message}. The local library stays available.")
            }
            step("telegram monitor") { monitorTelegram() }
            // Live index updates + periodic background sync (WorkManager: survives process death).
            step("indexer") { indexer.start(appScope) }
            // External metadata: periodic retries/refresh; matching also follows index changes.
            step("metadata sync") { dev.yourstream.metadata.sync.MetadataSyncWorker.schedule(this@YourStreamApp) }
            step("maintenance") { MaintenanceWorker.schedule(this@YourStreamApp) }
        } else {
            journal.warn(DiagnosticCategory.STARTUP, "Safe mode: Telegram, indexing and background work not started")
        }
        // Survived long enough: this start was not part of a crash loop.
        delay(diagnostics.guard.stableAfterMs)
        diagnostics.guard.markStable()
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.events.error(DiagnosticCategory.STARTUP, "Startup step \"$name\" failed", e)
        }
    }

    /** Telegram state changes go to the journal: disconnections become visible in diagnostics. */
    private fun monitorTelegram() {
        val journal = diagnostics.events
        appScope.launch {
            telegramManager.status.collect { s ->
                when (s) {
                    is TelegramStatus.Failed -> journal.error(DiagnosticCategory.TELEGRAM, "TDLib failed: ${s.error.message}")
                    is TelegramStatus.NotConfigured -> journal.warn(DiagnosticCategory.TELEGRAM, "Telegram not configured: ${s.reason}")
                    else -> journal.info(DiagnosticCategory.TELEGRAM, "TDLib $s")
                }
            }
        }
        appScope.launch {
            telegramManager.connectionState.collect { c ->
                if (c == TelegramConnectionState.WAITING_FOR_NETWORK) journal.warn(DiagnosticCategory.NETWORK, "Telegram waiting for network")
                else journal.info(DiagnosticCategory.TELEGRAM, "Connection $c")
            }
        }
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            diagnostics.lastTrimLevel = level
            if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
            val critical = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            val n = MemoryPressure.trim(critical)
            diagnostics.events.info(DiagnosticCategory.MEMORY, "Trim memory level $level: $n caches released${if (critical) " (critical)" else ""}")
        }

        override fun onConfigurationChanged(newConfig: AndroidConfiguration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            MemoryPressure.trim(critical = true)
            diagnostics.events.warn(DiagnosticCategory.MEMORY, "Low memory")
        }
    }
}
