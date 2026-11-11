package dev.yourstream.media.indexer.live

import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.media.indexer.store.IndexStore
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramMessageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While the app runs, applies Telegram's live updates to the index of enabled chats: new media
 * appear immediately, permanent deletions and edits are reflected without waiting for a scan.
 *
 * It deliberately does NOT move scan cursors: a live insert says nothing about messages that
 * arrived while the app was closed, so the next head pass still walks down to its old mark.
 */
@Singleton
class LiveIndexUpdater @Inject constructor(
    private val messages: TelegramMessageRepository,
    private val files: TelegramFileRepository,
    private val store: IndexStore,
    private val normalizer: MediaNormalizer,
    private val logger: Logger,
    private val listeners: Set<@JvmSuppressWildcards MediaIndexListener>,
) {
    private var jobs: List<Job> = emptyList()

    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { messages.events.collect { runCatching { handle(it) }.onFailure { e -> logger.w(TAG, "live update failed", e) } } },
            scope.launch { files.fileUpdates.collect { runCatching { handleFile(it) } } },
        )
    }

    suspend fun handle(event: TelegramMessageEvent) {
        val chat = store.chat(event.chatId) ?: return
        if (!chat.enabled) return
        apply(event, chat)
        listeners.forEach { it.onIndexChanged() }
    }

    private suspend fun apply(event: TelegramMessageEvent, chat: dev.yourstream.media.indexer.store.ChatRecord) {
        when (event) {
            is TelegramMessageEvent.NewMediaMessage -> {
                val item = withContext(normalizer.normalize(event.message, chat.title, albumCaptionFor(event.message)) ?: return, event.message, chat.title)
                val result = store.upsert(listOf(item))
                if (result.added > 0) logger.d(TAG, "live: new media in chat ${event.chatId}")
            }
            is TelegramMessageEvent.MessagesDeleted -> {
                val n = store.markDeleted(event.chatId, event.messageIds)
                if (n > 0) logger.d(TAG, "live: $n media deleted in chat ${event.chatId}")
            }
            is TelegramMessageEvent.MessageChanged -> {
                val lookup = messages.lookupMessages(event.chatId, listOf(event.messageId))
                val found = (lookup as? AppResult.Ok)?.value?.firstOrNull() ?: return
                val item = (found as? MessageLookup.Found)?.let {
                    val stored = store.contextCaptions(event.chatId, listOf(event.messageId))[event.messageId]
                    normalizer.normalize(it.message, chat.title, albumCaptionFor(it.message), stored)
                }
                if (item != null) store.upsert(listOf(item)) else store.markDeleted(event.chatId, listOf(event.messageId))
            }
        }
    }

    /** Download progress is frequent; only settled states are written. */
    suspend fun handleFile(file: TelegramFile) {
        if (file.isDownloadingCompleted || !file.isDownloadingActive) store.updateDownload(file)
    }

    /**
     * A new file without a title of its own takes it from the post above it (posted a moment
     * before, usually): see ContextFinder.
     */
    private suspend fun withContext(item: dev.yourstream.media.parser.NormalizedMedia, m: dev.yourstream.telegram.api.model.TelegramMediaMessage, chatTitle: String?): dev.yourstream.media.parser.NormalizedMedia {
        if (!item.needsContext) return item
        val finder = dev.yourstream.media.indexer.engine.ContextFinder({ chat, from -> messages.history(chat, from, 30) })
        return when (val post = finder.find(m.chatId, m.messageId, m.date)) {
            null -> item
            "" -> item.copy(contextCaption = "")
            else -> normalizer.normalize(m, chatTitle, albumCaptionFor(m), post) ?: item
        }
    }

    /** A new album file without a caption takes the caption another file of its album carries. */
    private suspend fun albumCaptionFor(m: dev.yourstream.telegram.api.model.TelegramMediaMessage): String? =
        if (m.albumId == 0L || m.caption.isNotBlank()) null else store.albumCaption(m.chatId, m.albumId)

    private companion object {
        const val TAG = "IndexLive"
    }
}
