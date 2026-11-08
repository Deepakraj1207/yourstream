package dev.yourstream.metadata.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.yourstream.core.common.Clock
import dev.yourstream.metadata.core.MetadataCache
import java.util.concurrent.TimeUnit

/** Twice a day: retries that are due, stale metadata refresh, cache housekeeping. */
@HiltWorker
class MetadataSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val match: MatchLibraryMetadata,
    private val service: LibraryMetadataService,
    private val cache: MetadataCache,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        match()
        service.refreshStale()
        // Expired entries stay a week longer: they are the fallback when providers are down.
        cache.purgeExpired(clock.nowMillis(), keepStaleForMillis = 7L * 24 * 60 * 60 * 1000)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MetadataSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("metadata-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
