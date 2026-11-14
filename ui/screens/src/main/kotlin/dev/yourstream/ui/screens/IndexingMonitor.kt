package dev.yourstream.ui.screens

import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
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
    /** Messages read per second (smoothed); null until known. */
    val perSecond: Float? = null,
    /** Time left at that speed; null when unknown. */
    val etaSeconds: Long? = null,
) {
    val active: Boolean get() = chats > 0 || queued > 0
    val fraction: Float? get() = if (total > 0) (scanned.toFloat() / total).coerceIn(0f, 1f) else null

    companion object {
        /** Pure mapping from the index (tested). */
        fun of(chats: List<ChatIndexStatus>): IndexingStatus {
            val enabled = chats.filter { it.enabled }
            val active = enabled.filter { !it.paused }
            val running = active.filter { it.progress != null || it.state.isActive }
            val queued = active.count { it.progress == null && (it.state == IndexState.QUEUED || it.state == IndexState.RECOVERING) }
            return IndexingStatus(
                chats = running.size, queued = queued,
                found = running.sumOf { c -> c.progress?.let { it.mediaAdded + it.mediaUpdated } ?: 0 },
                scanned = running.sumOf { it.progress?.messagesSeen ?: 0 },
                total = running.sumOf { (it.progress?.estimatedTotal ?: 0).coerceAtLeast(0) },
            )
        }
    }
}

/** Live indexing progress for the status pill, with speed and time left. */
@Singleton
class IndexingMonitor(private val index: MediaIndexRepository, private val now: () -> Long) {
    @Inject constructor(index: MediaIndexRepository) : this(index, System::currentTimeMillis)

    val status: Flow<IndexingStatus> = index.observeChats().map(IndexingStatus::of).distinctUntilChanged()
        .scan(Speed()) { speed, s -> speed.next(s, now()) }
        .map { it.status }
        .distinctUntilChanged()
        // Pages are written many times a second: the status line moves at most once a second.
        .conflate()
        .transform { emit(it); kotlinx.coroutines.delay(THROTTLE_MS) }
        .catch { emit(IndexingStatus()) }

    private companion object {
        const val THROTTLE_MS = 1_000L
    }

    /** Messages read per second, smoothed, and the time left from it (pure; tested). */
    data class Speed(val status: IndexingStatus = IndexingStatus(), val at: Long = 0, val perSecond: Float = 0f) {
        fun next(s: IndexingStatus, time: Long): Speed {
            if (!s.active || at == 0L || s.scanned < status.scanned) return Speed(s, time, 0f)
            val seconds = (time - at) / 1000f
            if (seconds < 1f) return copy(status = s.copy(perSecond = status.perSecond, etaSeconds = status.etaSeconds))
            val instant = (s.scanned - status.scanned) / seconds
            val rate = if (perSecond == 0f) instant else perSecond * 0.7f + instant * 0.3f
            val left = s.total - s.scanned
            val eta = if (s.total > 0 && left > 0 && rate >= 0.5f) (left / rate).toLong() else null
            return Speed(s.copy(perSecond = rate.takeIf { it >= 0.5f }, etaSeconds = eta), time, rate)
        }
    }
}
