package dev.yourstream.playback.adaptive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the seek manager drives (the player controller in the app, a virtual player in benchmarks). */
interface SeekTarget {
    fun applySeek(positionMs: Long)
    fun setFastSeek(enabled: Boolean)
}

data class SeekConfig(
    /** Seeks arriving within this window after an applied seek are merged into one (trailing). */
    val coalesceWindowMs: Long = 150,
    /** While scrubbing, previews are applied at most this often, as key-frame seeks. */
    val scrubIntervalMs: Long = 300,
)

data class SeekStats(val requested: Int = 0, val applied: Int = 0, val coalesced: Int = 0, val focusCancelled: Int = 0)

/**
 * Smart seeking:
 *  1. **Prioritise the requested region / cancel unnecessary transfers.** Before the player seeks,
 *     prefetching is paused (its transfers are cancelled) and downloads of other files nobody is
 *     reading are cancelled ([onFocus], wired to `TelegramFileStreamer.focus`). The current file's
 *     download is re-pointed by the streamer as soon as the player opens the new position, and it
 *     starts with a small window that grows only if playback continues there.
 *  2. **Request the data immediately.** The first seek of a burst is applied at once (leading
 *     edge): the player opens the data source at the target within milliseconds.
 *  3. **Avoid wasting bandwidth.** Further seeks within [SeekConfig.coalesceWindowMs] (rapid taps,
 *     ±10 s presses, scrubbing) are merged: only the last target is applied when the window ends
 *     (trailing edge), so intermediate positions never trigger downloads. Scrubbing applies cheap
 *     key-frame previews at a bounded rate, then one exact seek at the end.
 *
 * Prefetch resumes when the player reports the seek complete ([onSeekCompleted]).
 */
class SeekManager(
    private val target: SeekTarget,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val governor: DownloadGovernor? = null,
    private val onFocus: suspend () -> Int = { 0 },
    private val config: SeekConfig = SeekConfig(),
) {
    private var lastAppliedAt = Long.MIN_VALUE / 2
    private var pending: Long? = null
    private var trailing: Job? = null
    private var scrubbing = false
    var stats = SeekStats(); private set
    val hasPendingSeek: Boolean get() = pending != null

    /** Seek requested by the user / UI. */
    @Synchronized fun seekTo(positionMs: Long) {
        stats = stats.copy(requested = stats.requested + 1)
        val window = if (scrubbing) config.scrubIntervalMs else config.coalesceWindowMs
        val now = clock()
        if (now - lastAppliedAt >= window && trailing == null) {
            apply(positionMs, now)
        } else {
            if (pending != null) stats = stats.copy(coalesced = stats.coalesced + 1)
            pending = positionMs
            if (trailing == null) {
                val wait = (lastAppliedAt + window - now).coerceAtLeast(1)
                trailing = scope.launch {
                    delay(wait)
                    synchronized(this@SeekManager) {
                        trailing = null
                        pending?.let { p -> pending = null; apply(p, clock()) }
                    }
                }
            }
        }
    }

    /** Scrubbing starts (finger down on the seek bar): key-frame previews, rate limited. */
    @Synchronized fun beginScrub() {
        scrubbing = true
        target.setFastSeek(true)
    }

    /** Scrubbing ends at [positionMs]: one exact seek; anything pending is dropped. */
    @Synchronized fun endScrub(positionMs: Long) {
        scrubbing = false
        trailing?.cancel(); trailing = null
        if (pending != null) stats = stats.copy(coalesced = stats.coalesced + 1)
        pending = null
        target.setFastSeek(false)
        stats = stats.copy(requested = stats.requested + 1)
        apply(positionMs, clock())
    }

    /** The player is ready at the new position: background transfers may continue. */
    fun onSeekCompleted() { governor?.resumePrefetch(PAUSE_REASON) }

    private fun apply(positionMs: Long, now: Long) {
        lastAppliedAt = now
        stats = stats.copy(applied = stats.applied + 1)
        scope.launch {
            governor?.pausePrefetch(PAUSE_REASON)
            val cancelled = onFocus()
            if (cancelled > 0) synchronized(this@SeekManager) { stats = stats.copy(focusCancelled = stats.focusCancelled + cancelled) }
        }
        target.applySeek(positionMs)
    }

    companion object { const val PAUSE_REASON = "seek" }
}
