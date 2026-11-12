package dev.yourstream.media.indexer.store

import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexRunResult
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.media.parser.NormalizedMedia
import dev.yourstream.telegram.api.model.MediaSearchFilter
import dev.yourstream.telegram.api.model.TelegramFile

/** What the engine knows about a chat it is about to index. */
data class ChatRecord(
    val chatId: Long,
    /** Used as a last-resort title hint for generic file names. */
    val title: String?,
    val enabled: Boolean,
    /** Paused by the user: runs end before scanning. */
    val paused: Boolean = false,
    val verifyCursor: Long,
    val lastVerifiedAt: Long?,
)

/** Position of one filter's scan. See ScanCursorEntity for field meanings. */
data class ScanCursor(
    val chatId: Long,
    val filter: MediaSearchFilter,
    val newestMessageId: Long = 0,
    val backfillFromMessageId: Long = 0,
    val backfillComplete: Boolean = false,
    val estimatedTotal: Int = -1,
)

data class WriteResult(val added: Int = 0, val updated: Int = 0, val unchanged: Int = 0) {
    operator fun plus(other: WriteResult) = WriteResult(added + other.added, updated + other.updated, unchanged + other.unchanged)
}

/**
 * Persistence used by the indexing engine. Every method is atomic; [savePage] writes a page of
 * media and the advanced cursor in one transaction, so a crash can never record a cursor
 * position whose media were not stored (or the reverse).
 */
interface IndexStore {
    suspend fun chat(chatId: Long): ChatRecord?

    /** The caption one file of an album carries (Telegram stores it once per album), if any. */
    suspend fun albumCaption(chatId: Long, albumId: Long): String? = null

    /** Stored posts-above of these messages (only files whose title came from one). */
    suspend fun contextCaptions(chatId: Long, messageIds: List<Long>): Map<Long, String> = emptyMap()

    /** Video files of [chatId] below [beforeMessageId] with no title of their own and no post above looked up yet, newest first. */
    suspend fun untitledWithoutContext(chatId: Long, beforeMessageId: Long, limit: Int): List<UntitledFile> = emptyList()

    /** Records the post above [file] ("" = none) and re-parses the file with it. */
    suspend fun applyContext(file: UntitledFile, context: String) {}
    suspend fun cursor(chatId: Long, filter: MediaSearchFilter): ScanCursor
    suspend fun saveCursor(cursor: ScanCursor)
    suspend fun resetCursors(chatId: Long)

    suspend fun savePage(items: List<NormalizedMedia>, cursor: ScanCursor?): WriteResult

    /** Live updates and verification: same upsert, no cursor. */
    suspend fun upsert(items: List<NormalizedMedia>): WriteResult = savePage(items, null)

    /** Marks live records of these messages deleted. Returns how many changed. */
    suspend fun markDeleted(chatId: Long, messageIds: List<Long>): Int

    suspend fun touch(chatId: Long, messageIds: List<Long>)

    suspend fun liveMessageIdsAfter(chatId: Long, afterMessageId: Long, limit: Int): List<Long>
    suspend fun setVerifyCursor(chatId: Long, cursor: Long, finished: Boolean)

    suspend fun setState(chatId: Long, state: IndexState, error: String? = null)
    suspend fun markIndexed(chatId: Long)

    suspend fun startRun(chatId: Long, mode: IndexMode): Long
    suspend fun finishRun(runId: Long, result: IndexRunResult)

    suspend fun isEnabled(chatId: Long): Boolean

    /** Records a download state change for files the index knows (by session file id). */
    suspend fun updateDownload(file: TelegramFile)
}

/** A file whose title is missing or only guessed from its chat's name. */
data class UntitledFile(
    val id: Long,
    val messageId: Long,
    /** Epoch seconds. */
    val messageDate: Long,
    val fileName: String,
    val caption: String,
    val audioTitle: String?,
    val audioPerformer: String?,
    val chatTitle: String?,
)
