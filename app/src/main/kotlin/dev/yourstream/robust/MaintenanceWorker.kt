package dev.yourstream.robust

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yourstream.core.database.DatabaseIntegrity
import dev.yourstream.core.database.OpenedDatabase
import dev.yourstream.core.diagnostics.DiagnosticCategory
import java.util.concurrent.TimeUnit

/**
 * Weekly housekeeping, only while charging and idle: database integrity check and repair,
 * watch-history trim, WAL checkpoint. Never runs on battery.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val opened: OpenedDatabase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val diagnostics = AppDiagnostics.get(applicationContext)
        return try {
            val sql = opened.db.openHelper.writableDatabase
            val report = DatabaseIntegrity.check(sql, opened.file)
            if (!report.ok) {
                val repair = DatabaseIntegrity.repair(sql, opened.file)
                diagnostics.events.warn(DiagnosticCategory.DATABASE, "Maintenance repaired the database: ${repair.actions.joinToString("; ")}")
            }
            opened.db.watchDao().trimHistory(5_000)
            diagnostics.events.info(DiagnosticCategory.WORK, "Maintenance done (integrity ${if (report.ok) "ok" else "repaired"})")
            Result.success()
        } catch (e: Exception) {
            diagnostics.events.error(DiagnosticCategory.WORK, "Maintenance failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val NAME = "maintenance"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(7, TimeUnit.DAYS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
