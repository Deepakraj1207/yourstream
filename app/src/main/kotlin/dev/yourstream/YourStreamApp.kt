package dev.yourstream

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.yourstream.di.ApplicationScope
import dev.yourstream.media.indexer.IndexerInitializer
import dev.yourstream.telegram.tdlib.TelegramInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YourStreamApp : Application(), Configuration.Provider {

    @Inject lateinit var telegram: TelegramInitializer
    @Inject lateinit var indexer: IndexerInitializer
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Starts TDLib; a saved session resumes without a new login.
            telegram.start()
            // Live index updates + periodic background sync.
            indexer.start(appScope)
            // External metadata: periodic retries/refresh; matching also follows index changes.
            dev.yourstream.metadata.sync.MetadataSyncWorker.schedule(this@YourStreamApp)
        }
    }
}
