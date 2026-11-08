package dev.yourstream.playback.adaptive

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class TransferClass { PLAYBACK, PREFETCH, OTHER }

/** Configurable bandwidth protection. */
data class TransferLimits(
    /** Concurrent Telegram downloads started through the governor, all classes together. */
    val maxConcurrentDownloads: Int = 2,
    /** Of those, prefetches. */
    val maxConcurrentPrefetches: Int = 1,
    /** Bytes prefetch may download per playback session (all prefetched files together). */
    val prefetchBudgetBytes: Long = 32L * 1024 * 1024,
    /** Download priority at or above which a request counts as playback (the streamer uses 32). */
    val playbackPriorityAtLeast: Int = 24,
    /** At or below which a request is a prefetch. */
    val prefetchPriorityAtMost: Int = 8,
) {
    init {
        require(maxConcurrentDownloads >= 1 && maxConcurrentPrefetches in 0..maxConcurrentDownloads)
    }
}

data class GovernorStats(
    val active: Map<Int, TransferClass> = emptyMap(),
    val peakConcurrent: Int = 0,
    val admitted: Int = 0,
    /** Lower-priority downloads cancelled to make room. */
    val preempted: Int = 0,
    /** Requests refused (limit reached, prefetch paused or over budget). */
    val rejected: Int = 0,
    val bytesByClass: Map<TransferClass, Long> = emptyMap(),
    val prefetchPaused: Boolean = false,
)

/**
 * Every Telegram download of the playback stack goes through here (the streamer and the
 * prefetcher both get this repository). It guarantees there are never more than
 * [TransferLimits.maxConcurrentDownloads] downloads, with playback first:
 *
 *  - a playback request is always admitted; if the limit is reached the lowest-priority other
 *    download (a prefetch first, then the oldest) is cancelled;
 *  - a prefetch is admitted only while prefetching is not paused, below its own concurrency
 *    limit, within its byte budget, and if a slot is free (it never preempts anything);
 *  - a download leaves the active set when Telegram reports it finished/stopped or it is cancelled.
 *
 * Also measures bytes per class (bandwidth accounting for the benchmarks).
 */
class DownloadGovernor(
    private val delegate: TelegramFileRepository,
    scope: CoroutineScope,
    val limits: TransferLimits = TransferLimits(),
) : TelegramFileRepository by delegate {

    private class Active(val cls: TransferClass, val priority: Int, val order: Long, var lastSize: Long)

    private val active = ConcurrentHashMap<Int, Active>()
    private var order = 0L
    @Volatile private var prefetchPausedBy = emptySet<String>()
    private var prefetchBytes = 0L
    private val _stats = MutableStateFlow(GovernorStats())
    val stats: StateFlow<GovernorStats> = _stats.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { delegate.fileUpdates.collect(::onUpdate) }
    }

    fun classOf(priority: Int) = when {
        priority >= limits.playbackPriorityAtLeast -> TransferClass.PLAYBACK
        priority <= limits.prefetchPriorityAtMost -> TransferClass.PREFETCH
        else -> TransferClass.OTHER
    }

    val prefetchBudgetLeft: Long get() = synchronized(this) { (limits.prefetchBudgetBytes - prefetchBytes).coerceAtLeast(0) }

    override suspend fun download(fileId: Int, priority: Int, offset: Long, limit: Long): AppResult<TelegramFile> {
        val cls = classOf(priority)
        val victims = mutableListOf<Int>()
        synchronized(this) {
            val existing = active[fileId]
            if (existing == null) {
                if (cls == TransferClass.PREFETCH) {
                    val reason = when {
                        prefetchPausedBy.isNotEmpty() -> "prefetch paused (${prefetchPausedBy.joinToString()})"
                        prefetchBytes >= limits.prefetchBudgetBytes -> "prefetch budget used"
                        active.values.count { it.cls == TransferClass.PREFETCH } >= limits.maxConcurrentPrefetches -> "prefetch concurrency limit"
                        active.size >= limits.maxConcurrentDownloads -> "no free download slot"
                        else -> null
                    }
                    if (reason != null) {
                        bump { it.copy(rejected = it.rejected + 1) }
                        return AppResult.Err(AppError.InvalidState(reason))
                    }
                }
                while (active.size >= limits.maxConcurrentDownloads) {
                    // Lowest priority first (oldest among equals); a playback request may also replace an older playback.
                    val victim = active.entries.filter { it.value.priority < priority }
                        .minWithOrNull(compareBy<Map.Entry<Int, Active>> { it.value.priority }.thenBy { it.value.order })
                        ?: active.entries.takeIf { cls == TransferClass.PLAYBACK }?.minByOrNull { it.value.order }
                        ?: break
                    active.remove(victim.key)
                    victims += victim.key
                }
                if (active.size >= limits.maxConcurrentDownloads) {
                    bump { it.copy(rejected = it.rejected + 1) }
                    return AppResult.Err(AppError.InvalidState("download limit reached"))
                }
            }
            active[fileId] = Active(cls, priority, existing?.order ?: order++, existing?.lastSize ?: -1)
            bump { it.copy(admitted = it.admitted + 1, preempted = it.preempted + victims.size, peakConcurrent = maxOf(it.peakConcurrent, active.size)) }
        }
        victims.forEach { delegate.cancelDownload(it) }
        val r = delegate.download(fileId, priority, offset, limit)
        when (r) {
            is AppResult.Ok -> synchronized(this) { active[fileId]?.let { if (it.lastSize < 0) it.lastSize = r.value.downloadedSize } }
            is AppResult.Err -> synchronized(this) { active.remove(fileId); publish() }
        }
        return r
    }

    override suspend fun cancelDownload(fileId: Int): AppResult<Unit> {
        synchronized(this) { active.remove(fileId); publish() }
        return delegate.cancelDownload(fileId)
    }

    /** Pauses prefetching for [reason] (seek, rebuffer risk, poor network …) and cancels running prefetches. */
    suspend fun pausePrefetch(reason: String) {
        val cancel: List<Int>
        synchronized(this) {
            prefetchPausedBy = prefetchPausedBy + reason
            cancel = active.filterValues { it.cls == TransferClass.PREFETCH }.keys.toList()
            cancel.forEach { active.remove(it) }
            publish()
        }
        cancel.forEach { delegate.cancelDownload(it) }
    }

    fun resumePrefetch(reason: String) = synchronized(this) { prefetchPausedBy = prefetchPausedBy - reason; publish() }

    val isPrefetchPaused: Boolean get() = prefetchPausedBy.isNotEmpty()

    fun resetPrefetchBudget() = synchronized(this) { prefetchBytes = 0 }

    private fun onUpdate(f: TelegramFile) = synchronized(this) {
        val a = active[f.id] ?: return@synchronized
        if (a.lastSize >= 0 && f.downloadedSize > a.lastSize) {
            val delta = f.downloadedSize - a.lastSize
            if (a.cls == TransferClass.PREFETCH) prefetchBytes += delta
            bump { it.copy(bytesByClass = it.bytesByClass + (a.cls to ((it.bytesByClass[a.cls] ?: 0) + delta))) }
        }
        a.lastSize = f.downloadedSize
        if (f.isDownloadingCompleted || !f.isDownloadingActive) active.remove(f.id)
        publish()
    }

    private inline fun bump(change: (GovernorStats) -> GovernorStats) { _stats.value = change(_stats.value).copy(active = active.mapValues { it.value.cls }, prefetchPaused = prefetchPausedBy.isNotEmpty()) }
    private fun publish() = bump { it }
}
