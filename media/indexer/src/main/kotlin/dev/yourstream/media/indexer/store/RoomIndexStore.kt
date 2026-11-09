package dev.yourstream.media.indexer.store

import androidx.room.withTransaction
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.IndexRunEntity
import dev.yourstream.core.database.entity.ScanCursorEntity
import dev.yourstream.media.api.model.DownloadState
import dev.yourstream.media.api.model.DuplicateKind
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.parser.NormalizedMedia
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.TelegramFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomIndexStore @Inject constructor(
    private val db: YourStreamDatabase,
    private val clock: Clock,
) : IndexStore {

    private val chats = db.indexedChatDao()
    private val cursors = db.scanCursorDao()
    private val media = db.mediaDao()
    private val runs = db.indexRunDao()

    override suspend fun chat(chatId: Long): ChatRecord? = chats.get(chatId)?.let {
        ChatRecord(it.chatId, it.title, it.enabled, it.verifyCursor, it.lastVerifiedAt)
    }

    override suspend fun cursor(chatId: Long, filter: MediaSearchFilter): ScanCursor =
        cursors.get(chatId, filter.name)?.let {
            ScanCursor(chatId, filter, it.newestMessageId, it.backfillFromMessageId, it.backfillComplete, it.estimatedTotal)
        } ?: ScanCursor(chatId, filter)

    override suspend fun saveCursor(cursor: ScanCursor) = cursors.upsert(cursor.toEntity())

    override suspend fun resetCursors(chatId: Long) = cursors.deleteForChat(chatId)

    override suspend fun savePage(items: List<NormalizedMedia>, cursor: ScanCursor?): WriteResult =
        db.withTransaction {
            val now = clock.nowMillis()
            var result = WriteResult()
            for (item in items) result += upsertOne(item, now)
            cursor?.let { cursors.upsert(it.toEntity()) }
            result
        }

    /**
     * Insert or update by (chat, message), the natural key. Re-seeing a message is therefore
     * idempotent: overlapping pages, re-scans and live updates never create a second row.
     */
    private suspend fun upsertOne(item: NormalizedMedia, now: Long): WriteResult {
        val existing = media.findKey(item.chatId, item.messageId)
        val id: Long
        val needsDuplicateCheck: Boolean
        val outcome: WriteResult
        if (existing == null) {
            id = media.insert(item.toEntity(id = 0, indexedAt = now, now = now, canonicalMediaId = null, duplicateKind = null))
            needsDuplicateCheck = true
            outcome = WriteResult(added = 1)
        } else {
            val fileChanged = existing.fileUniqueId != item.fileUniqueId
            id = existing.id
            media.update(
                item.toEntity(
                    id = id,
                    indexedAt = existing.indexedAt,
                    now = now,
                    canonicalMediaId = if (fileChanged) null else existing.canonicalMediaId,
                    duplicateKind = if (fileChanged) null else existing.duplicateKind,
                ),
            )
            needsDuplicateCheck = fileChanged || existing.isDeleted
            outcome = if (fileChanged || existing.isDeleted) WriteResult(updated = 1) else WriteResult(unchanged = 1)
        }
        media.upsertParsed(item.parsed.toEntity(id))
        if (needsDuplicateCheck) resolveDuplicate(id, item.fileUniqueId, item.fingerprint)
        return outcome
    }

    /**
     * Duplicate groups are stars: every duplicate points at the group's canonical row, which
     * is the oldest live one (smallest id). EXACT = same Telegram upload (file_unique_id stays
     * the same across forwards); PROBABLE = same size + normalised name, different upload.
     */
    private suspend fun resolveDuplicate(id: Long, fileUniqueId: String, fingerprint: String?) {
        val match = media.oldestByUniqueId(fileUniqueId, id)?.let { it to DuplicateKind.EXACT }
            ?: fingerprint?.let { fp -> media.oldestByFingerprint(fp, id)?.let { it to DuplicateKind.PROBABLE } }
        if (match == null) {
            media.setCanonical(id, null, null)
            return
        }
        val (otherId, kind) = match
        val root = media.canonicalOf(otherId) ?: otherId
        if (id < root) {
            // An older row (re-checked) joins a younger group: it becomes the canonical one.
            media.setCanonical(id, null, null)
            media.repointDependents(root, id)
            media.setCanonical(root, id, kind.name)
        } else {
            media.setCanonical(id, root, kind.name)
        }
    }

    override suspend fun markDeleted(chatId: Long, messageIds: List<Long>): Int {
        if (messageIds.isEmpty()) return 0
        return db.withTransaction {
            val now = clock.nowMillis()
            var count = 0
            for (chunk in messageIds.chunked(SQL_CHUNK)) {
                val ids = media.liveIdsForMessages(chatId, chunk)
                if (ids.isEmpty()) continue
                media.markDeleted(ids, now)
                count += ids.size
                // A deleted canonical row dissolves its group; the survivors are re-grouped
                // oldest first, so the oldest becomes canonical and every kind (EXACT or
                // PROBABLE) again describes the relation to the new canonical row.
                for (deletedId in ids) {
                    val survivors = media.dependentsOf(deletedId)
                    if (survivors.isEmpty()) continue
                    survivors.forEach { media.setCanonical(it, null, null) }
                    for (survivor in survivors) {
                        val key = media.duplicateKey(survivor) ?: continue
                        resolveDuplicate(survivor, key.fileUniqueId, key.fingerprint)
                    }
                }
            }
            count
        }
    }

    override suspend fun touch(chatId: Long, messageIds: List<Long>) {
        val now = clock.nowMillis()
        messageIds.chunked(SQL_CHUNK).forEach { media.touch(chatId, it, now) }
    }

    override suspend fun contextCaptions(chatId: Long, messageIds: List<Long>): Map<Long, String> =
        messageIds.chunked(500).flatMap { media.contextCaptions(chatId, it) }.associate { it.messageId to it.contextCaption }

    override suspend fun untitledWithoutContext(chatId: Long, beforeMessageId: Long, limit: Int): List<UntitledFile> =
        media.untitledWithoutContext(chatId, beforeMessageId, limit).map {
            UntitledFile(it.id, it.messageId, it.messageDate, it.fileName, it.caption, it.audioTitle, it.audioPerformer, it.chatTitle)
        }

    override suspend fun applyContext(file: UntitledFile, context: String) = db.withTransaction {
        media.setContextCaption(file.id, context)
        if (context.isNotBlank()) {
            val parsed = dev.yourstream.media.parser.ParsedMediaMapper.parse(file.fileName, file.caption, file.chatTitle, context)
            media.upsertParsed(parsed.toEntity(file.id))
            media.setSearchTitle(file.id, dev.yourstream.media.parser.MediaNormalizer.searchTitle(parsed, file.audioTitle, file.audioPerformer))
        }
    }

    override suspend fun albumCaption(chatId: Long, albumId: Long): String? =
        if (albumId == 0L) null else media.albumCaption(chatId, albumId)

    override suspend fun liveMessageIdsAfter(chatId: Long, afterMessageId: Long, limit: Int): List<Long> =
        media.liveMessageIdsAfter(chatId, afterMessageId, limit)

    override suspend fun setVerifyCursor(chatId: Long, cursor: Long, finished: Boolean) {
        val now = clock.nowMillis()
        chats.setVerifyCursor(chatId, if (finished) 0 else cursor, if (finished) now else null, now)
    }

    override suspend fun setState(chatId: Long, state: IndexState, error: String?) =
        chats.setState(chatId, state.name, error, clock.nowMillis())

    override suspend fun markIndexed(chatId: Long) = chats.setIndexedAt(chatId, clock.nowMillis())

    override suspend fun startRun(chatId: Long, mode: IndexMode): Long =
        runs.insert(IndexRunEntity(chatId = chatId, mode = mode.name, startedAt = clock.nowMillis(), finishedAt = null,
            outcome = null, pages = 0, messagesSeen = 0, mediaAdded = 0, mediaUpdated = 0, mediaDeleted = 0, error = null))

    override suspend fun finishRun(runId: Long, result: IndexRunResult) {
        val started = runs.get(runId)?.startedAt ?: clock.nowMillis()
        runs.update(
            IndexRunEntity(
                id = runId, chatId = result.chatId, mode = result.mode.name, startedAt = started,
                finishedAt = clock.nowMillis(), outcome = result.outcome.name, pages = result.pagesScanned,
                messagesSeen = result.messagesSeen, mediaAdded = result.mediaAdded, mediaUpdated = result.mediaUpdated,
                mediaDeleted = result.mediaDeleted, error = result.error,
            ),
        )
        runs.trim(result.chatId, keep = RUN_HISTORY)
    }

    override suspend fun isEnabled(chatId: Long): Boolean = chats.isEnabled(chatId) == true

    override suspend fun updateDownload(file: TelegramFile) {
        val state = when {
            file.isDownloadingCompleted -> DownloadState.COMPLETE
            file.downloadedSize > 0 -> DownloadState.PARTIAL
            else -> DownloadState.NOT_DOWNLOADED
        }
        media.updateDownload(file.id, state.name, file.downloadedSize, file.localPath)
    }

    private fun ScanCursor.toEntity() = ScanCursorEntity(
        chatId = chatId,
        filter = filter.name,
        newestMessageId = newestMessageId,
        backfillFromMessageId = backfillFromMessageId,
        backfillComplete = backfillComplete,
        estimatedTotal = estimatedTotal,
        updatedAt = clock.nowMillis(),
    )

    private companion object {
        /** Stays far below SQLite's bound-parameter limit. */
        const val SQL_CHUNK = 500
        const val RUN_HISTORY = 20
    }
}
