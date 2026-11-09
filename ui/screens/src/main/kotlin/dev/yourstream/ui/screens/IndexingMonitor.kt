package dev.yourstream.ui.screens

import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What indexing is doing right now, summed over the chats being read. */
data class IndexingStatus(
    /** Chats being indexed at this moment. */
    val chats: Int = 0,
    /** Chats waiting for their turn. */
    val queued: Int = 0,
    /** Video files found (new or updated) in the running syncs. */
    val found: Int = 0,
    /** Messages read so far, and Telegram's estimate of the total (0 = unknown). */
    val scanned: Int = 0,
    val total: Int = 0,
) {
    val active: Boolean get() = chats > 0 || queued > 0
    val fraction: Float? get() = if (total > 0) (scanned.toFloat() / total).coerceIn(0f, 1f) else null

    companion object {
        /** Pure mapping from the index (tested). */
        fun of(chats: List<ChatIndexStatus>): IndexingStatus {
            val enabled = chats.filter { it.enabled }
            val running = enabled.filter { it.progress != null || it.state == IndexState.RUNNING }
            val queued = enabled.count { it.progress == null && (it.state == IndexState.QUEUED || it.state == IndexState.PAUSED) }
            return IndexingStatus(
                chats = running.size, queued = queued,
                found = running.sumOf { c -> c.progress?.let { it.mediaAdded + it.mediaUpdated } ?: 0 },
                scanned = running.sumOf { it.progress?.messagesSeen ?: 0 },
                total = running.sumOf { (it.progress?.estimatedTotal ?: 0).coerceAtLeast(0) },
            )
        }
    }
}

/** Live indexing progress for the status bar on Home. */
@Singleton
class IndexingMonitor @Inject constructor(private val index: MediaIndexRepository) {
    val status: Flow<IndexingStatus> = index.observeChats().map(IndexingStatus::of).distinctUntilChanged().catch { emit(IndexingStatus()) }
}
