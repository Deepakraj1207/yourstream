package dev.yourstream.playback.adaptive

import dev.yourstream.core.common.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A file that will probably be played next. */
data class PrefetchCandidate(val fileId: Int, val sizeBytes: Long, val durationMs: Long?, val label: String)

/** Knows what comes next (the library's next episode in the app). Null = nothing / unknown. */
fun interface NextMediaProvider {
    suspend fun next(): PrefetchCandidate?
}

data class PrefetchConfig(
    /** "Near completion": at most this much left… */
    val nearEndRemainingMs: Long = 120_000,
    /** …or this fraction played. */
    val nearEndFraction: Double = 0.90,
    /** Head of the next file: this much playback time… */
    val headSeconds: Long = 20,
    val minHeadBytes: Long = 2L * MB,
    val maxHeadBytes: Long = 12L * MB,
    /**
     * Tail of the next file: where MP4 `moov` (non-faststart) and MKV `Cues` usually sit. Players
     * read it before starting, so having it makes the next start one round trip shorter.
     */
    val tailBytes: Long = 1L * MB,
    /** Telegram priority for prefetch (playback uses 32; ≤ 8 is a prefetch for the governor). */
    val priority: Int = 4,
    val pollMs: Long = 250,
) {
    private companion object { const val MB = 1024L * 1024L }
}

enum class PrefetchStatus { IDLE, WAITING, HEAD, TAIL, DONE, PAUSED, NOTHING_NEXT, FAILED }

data class PrefetchState(
    val status: PrefetchStatus = PrefetchStatus.IDLE,
    val candidate: PrefetchCandidate? = null,
    val headBytes: Long = 0,
    val prefetchedBytes: Long = 0,
    val reason: String? = null,
    /** Set when the prefetched file actually started playing. */
    val used: Boolean = false,
)

/**
 * Next-episode prefetch, deliberately partial: when the current episode is near its end, the
 * buffer policy allows it (good network, no rebuffer risk, not metered unless allowed) and a next
 * episode is known, download only
 *  - its **head** (≈ 20 s of playback, 2–12 MB): enough for an instant start, and
 *  - its **tail** (1 MB): the container index for moov-at-end MP4s / MKV cues,
 * at low priority through the [DownloadGovernor] (one prefetch at a time, byte budget, paused
 * during seeks and whenever playback needs the bandwidth). Never the whole episode: if the user
 * stops, at most head + tail were spent.
 */
class PrefetchManager(
    private val governor: DownloadGovernor,
    private val provider: NextMediaProvider,
    private val scope: CoroutineScope,
    private val config: PrefetchConfig = PrefetchConfig(),
) {
    @Volatile var state = PrefetchState(); private set
    private var job: Job? = null
    private var resolved = false
    @Volatile private var allowed = false

    /** Called on every playback sample with the current policy. */
    @Synchronized fun onSample(sample: PlaybackSample, policy: BufferPolicy) {
        allowed = policy.prefetchAllowed
        val duration = sample.durationMs ?: return
        val nearEnd = duration - sample.positionMs <= config.nearEndRemainingMs || sample.positionMs >= duration * config.nearEndFraction
        if (!nearEnd || job != null || state.status in setOf(PrefetchStatus.DONE, PrefetchStatus.NOTHING_NEXT)) return
        if (!policy.prefetchAllowed) { state = state.copy(status = PrefetchStatus.WAITING, reason = policy.reason); return }
        job = scope.launch { run() }
    }

    /** The next file is now being played: whatever was prefetched gets used. */
    @Synchronized fun onPlaying(fileId: Int) {
        if (state.candidate?.fileId == fileId) state = state.copy(used = true)
        job?.cancel(); job = null
    }

    /** A new file started playing: forget the previous decision (the next item is different now). */
    @Synchronized fun reset() {
        job?.cancel(); job = null
        resolved = false
        state = PrefetchState()
    }

    /** Playback session ends or switches elsewhere: stop prefetching (prefetched bytes stay in the cache). */
    @Synchronized fun cancel() {
        job?.cancel(); job = null
        state.candidate?.let { c -> scope.launch { if (state.status == PrefetchStatus.HEAD || state.status == PrefetchStatus.TAIL) governor.cancelDownload(c.fileId) } }
    }

    private suspend fun run() {
        val candidate = if (!resolved) provider.next().also { resolved = true } else state.candidate
        if (candidate == null) { state = PrefetchState(PrefetchStatus.NOTHING_NEXT); return }
        val file = (governor.getFile(candidate.fileId) as? AppResult.Ok)?.value
        if (file == null || file.isDownloadingCompleted) { state = PrefetchState(PrefetchStatus.DONE, candidate, reason = "already on device"); return }
        val bitrate = candidate.durationMs?.takeIf { it > 0 }?.let { candidate.sizeBytes * 1000 / it }
        val head = (bitrate?.times(config.headSeconds) ?: config.minHeadBytes).coerceIn(config.minHeadBytes, config.maxHeadBytes).coerceAtMost(candidate.sizeBytes)
        val tailStart = (candidate.sizeBytes - config.tailBytes).coerceAtLeast(head)
        val startBytes = file.downloadedSize
        state = PrefetchState(PrefetchStatus.HEAD, candidate, head)

        if (!fetch(candidate.fileId, 0, head, PrefetchStatus.HEAD)) return
        if (tailStart < candidate.sizeBytes && !fetch(candidate.fileId, tailStart, candidate.sizeBytes - tailStart, PrefetchStatus.TAIL)) return
        val total = ((governor.getFile(candidate.fileId) as? AppResult.Ok)?.value?.downloadedSize ?: 0) - startBytes
        state = state.copy(status = PrefetchStatus.DONE, prefetchedBytes = total.coerceAtLeast(0))
    }

    /** Downloads [offset, offset+length) at prefetch priority, pausing while not allowed. */
    private suspend fun fetch(fileId: Int, offset: Long, length: Long, status: PrefetchStatus): Boolean {
        var requested = false
        while (true) {
            val have = (governor.downloadedPrefixSize(fileId, offset) as? AppResult.Ok)?.value ?: 0
            if (have >= length) return true
            if (!allowed || governor.isPrefetchPaused) {
                if (requested) { governor.cancelDownload(fileId); requested = false }
                state = state.copy(status = PrefetchStatus.PAUSED, reason = if (governor.isPrefetchPaused) "paused by playback" else "not allowed now")
            } else if (!requested || !isActive(fileId)) {
                when (val r = governor.download(fileId, config.priority, offset + have, length - have)) {
                    is AppResult.Ok -> { requested = true; state = state.copy(status = status, reason = null) }
                    is AppResult.Err -> { requested = false; state = state.copy(status = PrefetchStatus.PAUSED, reason = r.error.message) }
                }
                if (governor.prefetchBudgetLeft <= 0) { state = state.copy(status = PrefetchStatus.DONE, reason = "budget used"); return false }
            }
            delay(config.pollMs)
        }
    }

    private suspend fun isActive(fileId: Int) = (governor.getFile(fileId) as? AppResult.Ok)?.value?.isDownloadingActive == true
}
