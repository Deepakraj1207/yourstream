package dev.yourstream.media.indexer

import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.media.api.MediaIndexListener
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
    private val listeners: Set<@JvmSuppressWildcards MediaIndexListener>,
) {
    suspend fun start(scope: CoroutineScope) {
        // A process that died mid-run left an active state behind (RUNNING before v13);
        // WorkManager runs that work again, from the saved cursors.
        val dao = db.indexedChatDao()
        dao.resetUnpausedStates(listOf("RUNNING", IndexState.CONNECTING.name, IndexState.SCANNING.name, IndexState.PROCESSING.name), IndexState.RECOVERING.name)
        // Before v13 PAUSED meant "time budget used up, continuation queued": that is QUEUED now.
        dao.resetUnpausedStates(listOf(IndexState.PAUSED.name), IndexState.QUEUED.name)
        live.start(scope)
        scheduler.ensurePeriodicSync()
        if (reparser.outdatedCount() > 0) scheduler.scheduleReparse()
        // Let listeners (the library) catch up with anything queued before this start.
        listeners.forEach { it.onIndexChanged() }
    }
}
