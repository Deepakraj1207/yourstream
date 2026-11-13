package dev.yourstream.media.indexer.local

import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.Logger
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceKind
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.indexer.store.IndexStore
import dev.yourstream.media.parser.MediaNormalizer
import dev.yourstream.media.parser.NormalizedMedia
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaFile
import dev.yourstream.telegram.api.model.TelegramMediaMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Indexes one local source: lists its videos ([LocalFileLister]), parses each name exactly like a
 * Telegram file's ([MediaNormalizer]), writes them to the same index (source LOCAL, `locator` =
 * the file's address), and marks files that are gone as deleted. The library, metadata matching,
 * search and watch history then treat them like any other file.
 *
 * Each scan lists the whole source (the system's index answers quickly, and no file is opened),
 * so it needs no cursor: re-running it is always safe.
 */
class LocalScanner @Inject constructor(
    private val db: YourStreamDatabase,
    private val store: IndexStore,
    private val lister: LocalFileLister,
    private val normalizer: MediaNormalizer,
    private val clock: Clock,
    private val logger: Logger,
    private val listeners: Set<@JvmSuppressWildcards MediaIndexListener>,
) {
    /** Makes sure [source] has its index row (after adding it, or after a sign-out emptied the index). */
    suspend fun ensureRow(source: LocalSource) {
        val chats = db.indexedChatDao()
        if (chats.get(source.id) != null) return
        val now = clock.nowMillis()
        chats.upsert(
            IndexedChatEntity(
                chatId = source.id, title = source.title, category = source.kind.name, enabled = true,
                state = IndexState.IDLE.name, lastError = null, lastIndexedAt = null, lastVerifiedAt = null,
                verifyCursor = 0, addedAt = now, updatedAt = now, source = SOURCE_LOCAL,
            ),
        )
    }

    suspend fun scan(source: LocalSource): IndexRunResult = withContext(Dispatchers.IO) {
        ensureRow(source)
        val runId = store.startRun(source.id, IndexMode.INCREMENTAL)
        store.setState(source.id, IndexState.SCANNING)
        val files = try {
            lister.list(source)
        } catch (e: SecurityException) {
            return@withContext finish(runId, source, failed = if (source.kind == LocalSourceKind.FOLDER) NO_FOLDER_ACCESS else NO_VIDEO_PERMISSION)
        } catch (e: Exception) {
            logger.w(TAG, "listing ${source.kind} failed", e)
            return@withContext finish(runId, source, failed = LISTING_FAILED)
        }

        store.setState(source.id, IndexState.PROCESSING)
        val items = files.mapNotNull { normalize(source, it) }
        var added = 0
        var updated = 0
        items.chunked(PAGE).forEach { page ->
            val w = store.savePage(page, cursor = null)
            added += w.added; updated += w.updated
        }
        // Files no longer listed were deleted or moved out of the folder.
        val present = items.mapTo(HashSet()) { it.messageId }
        val gone = liveIds(source.id).filterNot { it in present }
        val deleted = if (gone.isEmpty()) 0 else gone.chunked(PAGE).sumOf { store.markDeleted(source.id, it) }

        finish(runId, source, added = added, updated = updated, deleted = deleted, seen = files.size)
    }

    private suspend fun liveIds(chatId: Long): List<Long> {
        val out = mutableListOf<Long>()
        var after = 0L
        while (true) {
            val page = store.liveMessageIdsAfter(chatId, after, PAGE)
            out += page
            if (page.size < PAGE) return out
            after = page.last()
        }
    }

    private suspend fun finish(runId: Long, source: LocalSource, added: Int = 0, updated: Int = 0, deleted: Int = 0, seen: Int = 0, failed: String? = null): IndexRunResult {
        val result = IndexRunResult(
            chatId = source.id, mode = IndexMode.INCREMENTAL,
            outcome = if (failed == null) IndexRunResult.Outcome.COMPLETED else IndexRunResult.Outcome.FAILED,
            pagesScanned = 1, messagesSeen = seen, mediaAdded = added, mediaUpdated = updated, mediaDeleted = deleted, error = failed,
        )
        store.finishRun(runId, result)
        if (failed != null) {
            store.setState(source.id, IndexState.FAILED, failed)
        } else {
            store.setState(source.id, IndexState.COMPLETED)
            store.markIndexed(source.id)
        }
        if (added + updated + deleted > 0) listeners.forEach { it.onIndexChanged() }
        return result
    }

    /** A local file in the shape the parser reads (it was written for Telegram messages). */
    private fun normalize(source: LocalSource, f: LocalFile): NormalizedMedia? {
        val identity = "local:${source.id}:${f.key}"
        val file = TelegramFile(
            id = 0, remoteUniqueId = "$identity:${f.sizeBytes}:${f.modifiedAt}", remoteId = identity,
            size = f.sizeBytes, expectedSize = f.sizeBytes, localPath = null, canBeDownloaded = false,
            isDownloadingActive = false, isDownloadingCompleted = true, downloadOffset = 0, downloadedPrefixSize = f.sizeBytes, downloadedSize = f.sizeBytes,
        )
        val message = TelegramMediaMessage(
            chatId = source.id, messageId = messageIdOf(f.key), date = f.modifiedAt / 1000, editDate = 0, albumId = 0, caption = "",
            contentType = TelegramMediaContentType.VIDEO,
            media = TelegramMediaFile(
                file = file, fileName = f.name, mimeType = f.mimeType, durationSeconds = f.durationMs?.let { (it / 1000).toInt() },
                width = f.width, height = f.height, supportsStreaming = true, codec = null, title = null, performer = null, thumbnailFileId = null,
            ),
        )
        return normalizer.normalize(message)?.copy(locator = f.uri)
    }

    companion object {
        const val SOURCE_LOCAL = "LOCAL"
        private const val TAG = "LocalScanner"
        private const val PAGE = 500
        const val NO_FOLDER_ACCESS = "This folder can no longer be read. Remove it and add it again."
        const val NO_VIDEO_PERMISSION = "YourStream may no longer read the videos on this device. Allow it in Android's app settings."
        const val LISTING_FAILED = "The files could not be listed. Try again."

        /** A positive id from the file's stable key: the "message id" of a local file (pure; tested). */
        fun messageIdOf(key: String): Long {
            val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
            return (v and Long.MAX_VALUE).coerceAtLeast(1)
        }
    }
}
