package dev.yourstream.di

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.ui.core.ThumbnailSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Thumbnails of indexed Telegram videos, for titles without a matched poster. TDLib file ids are
 * only valid per session, so the message is looked up (from TDLib's local cache when it has it),
 * its thumbnail (a few KB) downloaded at high priority, and the local path remembered.
 */
@Singleton
class TelegramThumbnails @Inject constructor(
    private val messages: TelegramMessageRepository,
    private val files: TelegramFileRepository,
) : ThumbnailSource {
    private val paths = ConcurrentHashMap<String, String>()
    /** Missing thumbnails are not asked for again in this session. */
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val parallel = Semaphore(PARALLEL)

    override suspend fun localPath(chatId: Long, messageId: Long): String? {
        val key = "$chatId:$messageId"
        paths[key]?.let { if (File(it).exists()) return it }
        if (key in missing) return null
        return parallel.withPermit {
            val found = (messages.lookupMessages(chatId, listOf(messageId)) as? AppResult.Ok)?.value?.firstOrNull() as? MessageLookup.Found
            val fileId = found?.message?.media?.thumbnailFileId
            if (fileId == null) { if (found != null) missing += key; return@withPermit null }
            var file = (files.getFile(fileId) as? AppResult.Ok)?.value ?: return@withPermit null
            if (!file.isDownloadingCompleted) {
                files.download(fileId, priority = PRIORITY)
                file = withTimeoutOrNull(TIMEOUT) { files.observeFile(fileId).first { it.isDownloadingCompleted } } ?: return@withPermit null
            }
            file.localPath?.takeIf { it.isNotBlank() && File(it).exists() }?.also { paths[key] = it }
        }
    }

    private companion object {
        const val PARALLEL = 6
        /** Above background prefetch, below playback (32). */
        const val PRIORITY = 16
        val TIMEOUT = 20.seconds
    }
}
