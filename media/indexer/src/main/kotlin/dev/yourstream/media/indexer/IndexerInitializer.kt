package dev.yourstream.media.indexer

import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.live.LiveIndexUpdater
import dev.yourstream.media.indexer.reparse.MediaReparser
import dev.yourstream.media.indexer.work.IndexScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val auth: dev.yourstream.telegram.api.TelegramAuthManager,
    private val localSources: dagger.Lazy<dev.yourstream.media.api.LocalMediaSources>,
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
        // Files on this device: scanned each time Telegram becomes ready (this start, and after a
        // sign-in, which follows a sign-out that emptied the index). Picks up added and deleted files.
        scope.launch {
            auth.state.map { it == dev.yourstream.telegram.api.model.AuthState.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) runCatching { localSources.get().rescanAll() }
            }
        }
    }
}
