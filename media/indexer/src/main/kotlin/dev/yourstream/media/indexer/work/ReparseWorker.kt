package dev.yourstream.media.indexer.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yourstream.media.indexer.reparse.MediaReparser

/** Brings stored metadata up to the current parser version in the background. */
@HiltWorker
class ReparseWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reparser: MediaReparser,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        reparser.run(maxRows = MAX_ROWS_PER_RUN)
        // Large libraries take several runs; each run stays well inside WorkManager's limit.
        return if (reparser.outdatedCount() > 0) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ROWS_PER_RUN = 20_000
    }
}
