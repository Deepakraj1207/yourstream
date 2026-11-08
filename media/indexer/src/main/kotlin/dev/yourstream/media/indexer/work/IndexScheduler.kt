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

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

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

    fun ensurePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun request(chatId: Long, mode: IndexMode) = OneTimeWorkRequestBuilder<IndexChatWorker>()
        .setInputData(workDataOf(IndexChatWorker.KEY_CHAT_ID to chatId, IndexChatWorker.KEY_MODE to mode.name))
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(TAG)
        .build()

    companion object {
        const val TAG = "media-index"
        private const val PERIODIC_NAME = "media-index-periodic"
        fun uniqueName(chatId: Long) = "index-chat-$chatId"
    }
}
