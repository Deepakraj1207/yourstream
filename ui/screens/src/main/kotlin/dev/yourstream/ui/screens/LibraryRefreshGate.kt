package dev.yourstream.ui.screens

import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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

/**
 * Keeps library lists still while Telegram chats are being indexed: every new file would otherwise
 * re-query and re-lay out the rows under the user's finger. Updates are applied when the user
 * asks ([load], the "Load files" button) and automatically once indexing has finished.
 */
@Singleton
class LibraryRefreshGate @Inject constructor(private val index: MediaIndexRepository) {
    private val loads = MutableStateFlow(0)

    /** Increments on every "Load files". */
    val loadRequests: StateFlow<Int> = loads.asStateFlow()

    val indexing: Flow<IndexingStatus> = index.observeChats().map(IndexingStatus::of).distinctUntilChanged().catch { emit(IndexingStatus()) }

    /** Shows what has been indexed so far. */
    fun load() { loads.value++ }

    /**
     * [source], but while indexing only its first value and the values current at each [load];
     * live again once indexing stops (the latest value is applied then).
     */
    fun <T> hold(source: Flow<T>): Flow<T> = flow {
        var emitted = false
        var last: Any? = null
        var lastLoad = -1
        combine(source, indexing.map { it.active }.distinctUntilChanged(), loads) { v, busy, n -> Triple(v, busy, n) }.collect { (v, busy, n) ->
            val apply = !emitted || !busy || n != lastLoad
            lastLoad = n
            if (apply && (!emitted || v !== last)) {
                emitted = true
                last = v
                emit(v)
            }
        }
    }
}
