package dev.yourstream.media.indexer.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yourstream.media.api.MediaIndexer

/** Every few hours: an incremental run for each enabled chat (new media, continued backfill, checks). */
@HiltWorker
class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val indexer: MediaIndexer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        indexer.scheduleAll()
        return Result.success()
    }
}
