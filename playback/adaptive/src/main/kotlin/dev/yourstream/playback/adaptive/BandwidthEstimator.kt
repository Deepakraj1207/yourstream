package dev.yourstream.playback.adaptive

import kotlin.math.pow

/**
 * Throughput estimate from download samples, as two exponentially weighted moving averages
 * weighted by sample duration: a fast one (half-life 2 s) that reacts to drops, and a slow one
 * (half-life 8 s) that ignores spikes. The estimate is the *minimum* of both: quick to believe
 * bad news, slow to believe good news, which is what a buffer needs.
 *
 * Samples shorter than [minSampleNanos] or smaller than [minSampleBytes] are merged, because
 * Telegram reports progress per part and tiny samples are dominated by timing noise.
 */
class BandwidthEstimator(
    fastHalfLifeSec: Double = 2.0,
    slowHalfLifeSec: Double = 8.0,
    private val minTotalBytes: Long = 128 * 1024,
    private val minSampleBytes: Long = 16 * 1024,
    private val minSampleNanos: Long = 50_000_000,
) {
    private val fast = Ewma(fastHalfLifeSec)
    private val slow = Ewma(slowHalfLifeSec)
    private val latency = Ewma(4.0)
    private var totalBytes = 0L
    private var pendingBytes = 0L
    private var pendingNanos = 0L

    @Synchronized fun addSample(bytes: Long, nanos: Long) {
        if (bytes <= 0 || nanos <= 0) return
        pendingBytes += bytes
        pendingNanos += nanos
        if (pendingBytes < minSampleBytes || pendingNanos < minSampleNanos) return
        val seconds = pendingNanos / 1e9
        val bps = pendingBytes / seconds
        fast.add(seconds, bps)
        slow.add(seconds, bps)
        totalBytes += pendingBytes
        pendingBytes = 0; pendingNanos = 0
    }

    @Synchronized fun addLatency(ms: Long) { if (ms >= 0) latency.add(1.0, ms.toDouble()) }

    /** Conservative bytes/s, or null before [minTotalBytes] have been measured. */
    @Synchronized fun estimate(): Long? = if (totalBytes < minTotalBytes) null else minOf(fast.value(), slow.value()).toLong()

    @Synchronized fun latencyMs(): Long? = latency.valueOrNull()?.toLong()

    /** New network: forget the fast average entirely and most of the slow one. */
    @Synchronized fun onNetworkChanged() {
        fast.reset()
        slow.decay(0.25)
        totalBytes = (totalBytes * 0.25).toLong()
        pendingBytes = 0; pendingNanos = 0
        latency.reset()
    }

    private class Ewma(private val halfLife: Double) {
        private var estimate = 0.0
        private var weight = 0.0
        fun add(w: Double, v: Double) {
            val alpha = 0.5.pow(w / halfLife)
            estimate = v * (1 - alpha) + alpha * estimate
            weight += w
        }
        /** Zero-bias corrected (early estimates are not pulled towards 0). */
        fun value(): Double = if (weight == 0.0) 0.0 else estimate / (1 - 0.5.pow(weight / halfLife))
        fun valueOrNull(): Double? = if (weight == 0.0) null else value()
        fun reset() { estimate = 0.0; weight = 0.0 }
        fun decay(keep: Double) { val v = value(); weight *= keep; estimate = v * (1 - 0.5.pow(weight / halfLife)) }
    }
}
