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
        chats.setState(chatId, IndexState.QUEUED.name, null, clock.nowMillis())
        scheduler.schedule(chatId, mode)
    }

    override suspend fun scheduleAll() {
        chats.enabledChatIds().forEach { schedule(it, IndexMode.INCREMENTAL) }
    }

    override suspend fun cancel(chatId: Long) {
        tracker.requestCancel(chatId)
        scheduler.cancel(chatId)
        // If nothing was running, nobody consumes the request: record the state ourselves.
        if (tracker.progress.value[chatId] == null) {
            tracker.consumeCancelRequest(chatId)
            if (chats.get(chatId)?.state == IndexState.QUEUED.name) {
                chats.setState(chatId, IndexState.CANCELLED.name, "Cancelled", clock.nowMillis())
            }
        }
    }

    private companion object {
        const val TAG = "MediaIndexer"
    }
}
