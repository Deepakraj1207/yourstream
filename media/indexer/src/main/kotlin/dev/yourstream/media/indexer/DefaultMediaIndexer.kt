package dev.yourstream.media.indexer

import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.media.api.MediaIndexer
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.engine.IndexProgressTracker
import dev.yourstream.media.indexer.work.IndexScheduler
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.media.api.model.IndexingPolicy
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultMediaIndexer @Inject constructor(
    db: YourStreamDatabase,
    private val telegramChats: TelegramChatRepository,
    private val scheduler: IndexScheduler,
    private val tracker: IndexProgressTracker,
    private val clock: Clock,
    private val logger: Logger,
) : MediaIndexer {

    private val chats = db.indexedChatDao()

    override suspend fun enableChat(chatId: Long): AppResult<Unit> {
        val chat = when (val result = telegramChats.getChat(chatId)) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> return result
        }
        val now = clock.nowMillis()
        val existing = chats.get(chatId)
        chats.upsert(
            existing?.copy(title = chat.title, category = chat.category.name, enabled = true, updatedAt = now)
                ?: IndexedChatEntity(
                    chatId = chatId, title = chat.title, category = chat.category.name, enabled = true,
                    state = IndexState.IDLE.name, lastError = null, lastIndexedAt = null, lastVerifiedAt = null,
                    verifyCursor = 0, addedAt = now, updatedAt = now,
                ),
        )
        logger.i(TAG, "indexing enabled for chat $chatId")
        // Choosing a chat again means "index it": a pause from before is lifted.
        chats.setPaused(chatId, false, now)
        schedule(chatId, IndexMode.INCREMENTAL)
        return AppResult.Ok(Unit)
    }

    override suspend fun disableChat(chatId: Long, purge: Boolean) {
        cancel(chatId)
        if (purge) chats.delete(chatId) else chats.setEnabled(chatId, false, clock.nowMillis())
        logger.i(TAG, "indexing disabled for chat $chatId (purge=$purge)")
    }

    override suspend fun schedule(chatId: Long, mode: IndexMode) {
        if (chats.isEnabled(chatId) != true) return
        // Paused chats wait for resume(): periodic syncs and "sync now" leave them alone.
        if (chats.isPaused(chatId) == true) return
        chats.setState(chatId, IndexState.QUEUED.name, null, clock.nowMillis())
        scheduler.schedule(chatId, mode)
    }

    override suspend fun scheduleAll() {
        chats.enabledChatIds().forEach { schedule(it, IndexMode.INCREMENTAL) }
    }

    override suspend fun cancel(chatId: Long) = stop(chatId, IndexProgressTracker.StopReason.CANCEL, IndexState.CANCELLED)

    override suspend fun pause(chatId: Long) {
        if (chats.isEnabled(chatId) != true) return
        chats.setPaused(chatId, true, clock.nowMillis())
        stop(chatId, IndexProgressTracker.StopReason.PAUSE, IndexState.PAUSED)
        logger.i(TAG, "indexing paused for chat $chatId")
    }

    override suspend fun resume(chatId: Long) {
        if (chats.isEnabled(chatId) != true) return
        chats.setPaused(chatId, false, clock.nowMillis())
        chats.setState(chatId, IndexState.IDLE.name, null, clock.nowMillis())
        // Cursors are kept: the run continues where the chat stopped.
        schedule(chatId, IndexMode.INCREMENTAL)
        logger.i(TAG, "indexing resumed for chat $chatId")
    }

    override suspend fun pauseAll() = chats.enabledChatIds().forEach { pause(it) }

    override suspend fun resumeAll() = chats.enabledChatIds().forEach { resume(it) }

    override val policy: StateFlow<IndexingPolicy> get() = scheduler.policy

    override suspend fun setPolicy(policy: IndexingPolicy) {
        if (policy == scheduler.policy.value) return
        scheduler.setPolicy(policy)
        // Waiting and running work picks up the new conditions (a running run stops and continues).
        for (id in chats.enabledChatIds()) {
            if (chats.isPaused(id) == true) continue
            val state = chats.get(id)?.state?.let { s -> IndexState.entries.firstOrNull { it.name == s } }
            if (state != null && state.isPending) scheduler.reschedule(id)
        }
        logger.i(TAG, "indexing policy: wifiOnly=${policy.wifiOnly} batteryAware=${policy.batteryAware}")
    }

    /**
     * Stops queued and running work. A running run sees [reason] and ends in [stoppedState]; if
     * nothing was running nobody would consume it, so the state is recorded here.
     */
    private suspend fun stop(chatId: Long, reason: IndexProgressTracker.StopReason, stoppedState: IndexState) {
        tracker.requestStop(chatId, reason)
        scheduler.cancel(chatId)
        if (tracker.progress.value[chatId] == null) {
            tracker.consumeStopRequest(chatId)
            val state = chats.get(chatId)?.state?.let { s -> IndexState.entries.firstOrNull { it.name == s } }
            if (stoppedState == IndexState.PAUSED || state == null || state.isPending) {
                chats.setState(chatId, stoppedState.name, null, clock.nowMillis())
            }
        }
    }

    private companion object {
        const val TAG = "MediaIndexer"
    }
}
