package dev.yourstream.media.api

import androidx.paging.PagingData
import dev.yourstream.core.common.AppResult
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexingPolicy
import dev.yourstream.media.api.model.IndexedMedia
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.media.api.model.MediaSummary
import kotlinx.coroutines.flow.Flow

/** Read side of the media index. Large results are always paged, never loaded whole. */
interface MediaIndexRepository {
    fun search(query: MediaQuery, pageSize: Int = 50): Flow<PagingData<MediaSummary>>
    fun count(query: MediaQuery): Flow<Int>
    suspend fun getMedia(id: Long): IndexedMedia?

    /** Other records of the same file (forwards, re-uploads), including the canonical one. */
    suspend fun duplicatesOf(id: Long): List<MediaSummary>

    fun observeChats(): Flow<List<ChatIndexStatus>>
    fun observeChat(chatId: Long): Flow<ChatIndexStatus?>
}

/** Write side: which chats are indexed, and running the indexer. */
interface MediaIndexer {
    /** Adds the chat to the index (or re-enables it) and queues an incremental run. */
    suspend fun enableChat(chatId: Long): AppResult<Unit>

    /** Stops indexing the chat. With [purge], its records are deleted too. */
    suspend fun disableChat(chatId: Long, purge: Boolean)

    /** Queues a background run (WorkManager: survives app restarts, retried on failure). */
    suspend fun schedule(chatId: Long, mode: IndexMode = IndexMode.INCREMENTAL)

    /** Queues an incremental run for every enabled chat. */
    suspend fun scheduleAll()

    /**
     * Stops the current run of the chat; progress so far is kept and the next sync (or
     * [schedule]) continues from it.
     */
    suspend fun cancel(chatId: Long)

    /** Stops indexing the chat until [resume]: nothing is scanned, progress so far is kept. */
    suspend fun pause(chatId: Long)

    /** Lifts [pause] and continues from where the chat stopped (nothing is scanned twice). */
    suspend fun resume(chatId: Long)

    /** [pause] / [resume] for every enabled chat. */
    suspend fun pauseAll()
    suspend fun resumeAll()

    /** When background indexing may run (Wi-Fi only, not on a low battery). */
    val policy: kotlinx.coroutines.flow.StateFlow<IndexingPolicy>

    /** Changes [policy]; queued work is rescheduled under the new conditions. */
    suspend fun setPolicy(policy: IndexingPolicy)
}
