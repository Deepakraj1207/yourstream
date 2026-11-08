package dev.yourstream.playback.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import dev.yourstream.playback.adaptive.BufferPolicy

/**
 * Media3 [LoadControl] driven by the [BufferPolicy] in force (re-read on every call, so changes from
 * the buffer manager apply immediately). Allocation, track handling and back buffer are delegated
 * to a [DefaultLoadControl]; only the two decisions are adaptive:
 *  - continue loading: below minBuffer yes, at maxBuffer or the byte cap no, in between keep going
 *    if already loading (hysteresis, as DefaultLoadControl);
 *  - start playback: startPlaybackMs buffered (resumeAfterRebufferMs after a stall), or the byte
 *    cap reached.
 */
@OptIn(UnstableApi::class)
class AdaptiveLoadControl(
    private val policy: () -> BufferPolicy,
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        // Bounds for the delegate's allocator only; the thresholds below are the adaptive ones.
        .setBufferDurationsMs(2_500, 300_000, 1_000, 2_500)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build(),
) : LoadControl {
    @Volatile private var loading = true

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) { loading = true; delegate.onStopped(playerId) }
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val p = policy()
        val bufferedMs = mediaMs(parameters.bufferedDurationUs, parameters.playbackSpeed)
        val bytes = delegate.getAllocator(parameters.playerId).totalBytesAllocated.toLong()
        loading = when {
            bytes >= p.targetBufferBytes -> false
            bufferedMs < p.minBufferMs -> true
            bufferedMs >= p.maxBufferMs -> false
            else -> loading
        }
        return loading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val p = policy()
        val bufferedMs = mediaMs(parameters.bufferedDurationUs, parameters.playbackSpeed)
        val needed = if (parameters.rebuffering) p.resumeAfterRebufferMs else p.startPlaybackMs
        val bytes = delegate.getAllocator(parameters.playerId).totalBytesAllocated.toLong()
        return needed <= 0 || bufferedMs >= needed || bytes >= p.targetBufferBytes
    }

    /** Buffered media time in real playback time (2× speed halves it). */
    private fun mediaMs(us: Long, speed: Float): Long = if (us == C.TIME_UNSET) 0 else (us / 1000 / speed.coerceAtLeast(0.1f)).toLong()
}
