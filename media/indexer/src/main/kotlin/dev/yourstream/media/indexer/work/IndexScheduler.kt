package dev.yourstream.media.indexer.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.yourstream.media.api.model.IndexingPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.media.api.model.IndexMode
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager facade. One unique work chain per chat ("index-chat-<id>"): work for a chat never
 * runs twice in parallel, survives process death and reboots, and is retried with backoff.
 */
@Singleton
class IndexScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    // When background indexing may run (a device setting: kept when the account changes). Stored
    // here, not in the UI's settings, because workers in a fresh process schedule work too.
    private val prefs = context.getSharedPreferences("indexing_policy", Context.MODE_PRIVATE)
    private val _policy = MutableStateFlow(IndexingPolicy(prefs.getBoolean(KEY_WIFI, false), prefs.getBoolean(KEY_BATTERY, true)))
    val policy: StateFlow<IndexingPolicy> = _policy.asStateFlow()

    fun setPolicy(policy: IndexingPolicy) {
        prefs.edit().putBoolean(KEY_WIFI, policy.wifiOnly).putBoolean(KEY_BATTERY, policy.batteryAware).apply()
        _policy.value = policy
        ensurePeriodicSync(ExistingPeriodicWorkPolicy.UPDATE)
    }

    private val constraints: Constraints get() = constraintsFor(_policy.value)

    /**
     * INCREMENTAL keeps an already queued/running run (asking twice does nothing); other modes
     * are appended so they run right after the current one.
     */
    fun schedule(chatId: Long, mode: IndexMode) {
        val policy = if (mode == IndexMode.INCREMENTAL) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
        workManager.enqueueUniqueWork(uniqueName(chatId), policy, request(chatId, mode))
    }

    /** Called by a run that stopped on its time budget: continue right after it. */
    fun scheduleContinuation(chatId: Long, mode: IndexMode) {
        workManager.enqueueUniqueWork(uniqueName(chatId), ExistingWorkPolicy.APPEND_OR_REPLACE, request(chatId, mode))
    }

    fun cancel(chatId: Long) {
        workManager.cancelUniqueWork(uniqueName(chatId))
    }

    /** Queued or running work of the chat again under the current [policy] (a running run stops and continues). */
    fun reschedule(chatId: Long) {
        workManager.enqueueUniqueWork(uniqueName(chatId), ExistingWorkPolicy.REPLACE, request(chatId, IndexMode.INCREMENTAL))
    }

    /** Re-parses stored names after a parser upgrade (no network needed). */
    fun scheduleReparse() {
        val request = OneTimeWorkRequestBuilder<ReparseWorker>()
            // CPU-only bulk work: not on a low battery.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(REPARSE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun ensurePeriodicSync(existing: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(6, TimeUnit.HOURS)
            // The periodic sync never runs on a low battery, whatever the policy says.
            .setConstraints(constraintsFor(_policy.value.copy(batteryAware = true)))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, existing, request)
    }

    private fun request(chatId: Long, mode: IndexMode) =
        if (dev.yourstream.media.api.LocalSourceIds.isLocal(chatId)) localRequest(chatId) else telegramRequest(chatId, mode)

    /** Files on this device: no network needed; never on a low battery when the policy says so. */
    private fun localRequest(chatId: Long) = OneTimeWorkRequestBuilder<dev.yourstream.media.indexer.local.LocalScanWorker>()
        .setInputData(workDataOf(IndexChatWorker.KEY_CHAT_ID to chatId))
        .setConstraints(localConstraintsFor(_policy.value))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(TAG)
        .build()

    private fun telegramRequest(chatId: Long, mode: IndexMode) = OneTimeWorkRequestBuilder<IndexChatWorker>()
        .setInputData(workDataOf(IndexChatWorker.KEY_CHAT_ID to chatId, IndexChatWorker.KEY_MODE to mode.name))
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(TAG)
        .build()

    companion object {
        const val TAG = "media-index"
        private const val KEY_WIFI = "wifi_only"
        private const val KEY_BATTERY = "battery_aware"

        /** WorkManager conditions for [policy] (pure; tested). */
        fun constraintsFor(policy: IndexingPolicy): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (policy.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(policy.batteryAware)
            .build()
        /** Local scans read the system's index only: the battery rule applies, the network rule does not (pure; tested). */
        fun localConstraintsFor(policy: IndexingPolicy): Constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(policy.batteryAware)
            .build()
        private const val PERIODIC_NAME = "media-index-periodic"
        private const val REPARSE_NAME = "media-index-reparse"
        fun uniqueName(chatId: Long) = "index-chat-$chatId"
    }
}
