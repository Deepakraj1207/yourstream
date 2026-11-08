package dev.yourstream.media.indexer

import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.live.LiveIndexUpdater
import dev.yourstream.media.indexer.reparse.MediaReparser
import dev.yourstream.media.indexer.work.IndexScheduler
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** Call once per process, after Telegram has been started. */
@Singleton
class IndexerInitializer @Inject constructor(
    private val db: YourStreamDatabase,
    private val live: LiveIndexUpdater,
    private val scheduler: IndexScheduler,
    private val reparser: MediaReparser,
) {
    suspend fun start(scope: CoroutineScope) {
        // A process that died mid-run left RUNNING behind; WorkManager will run that work again.
        db.indexedChatDao().resetStates(listOf(IndexState.RUNNING.name), IndexState.PAUSED.name)
        live.start(scope)
        scheduler.ensurePeriodicSync()
        if (reparser.outdatedCount() > 0) scheduler.scheduleReparse()
    }
}
