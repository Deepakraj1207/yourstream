package dev.yourstream.playback.adaptive

import kotlin.math.pow

enum class PlaybackPhase { IDLE, STARTING, PLAYING, PAUSED, BUFFERING, SEEKING, ENDED }

/** What the player looks like right now (sampled ~2×/s). */
data class PlaybackSample(
    val nowMs: Long,
    val phase: PlaybackPhase,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val durationMs: Long?,
    /** Average media bitrate (file size / duration, or the stream format), bytes/s. */
    val mediaBytesPerSecond: Long?,
    val droppedFramesPerSecond: Double = 0.0,
    /** The player is fetching media. When it is not (buffer full), the buffer drains by design. */
    val loading: Boolean = true,
) {
    val bufferedAheadMs: Long get() = (bufferedPositionMs - positionMs).coerceAtLeast(0)
    val remainingMs: Long? get() = durationMs?.let { (it - positionMs).coerceAtLeast(0) }
}

/** Device capabilities that bound buffering. */
data class DeviceProfile(val memoryClassMb: Int = 256, val lowRam: Boolean = false, val cpuCores: Int = 8) {
    /** Memory the player's in-RAM media buffer may use. Disk read-ahead (Telegram cache) is separate and larger. */
    val memoryBufferBudgetBytes: Long
        get() = when {
            lowRam -> 12L * MB
            memoryClassMb < 192 -> 24L * MB
            memoryClassMb < 384 -> 48L * MB
            else -> 64L * MB
        }

    private companion object { const val MB = 1024L * 1024L }
}

enum class RebufferRisk { NONE, ELEVATED, HIGH }

/** The buffering behaviour in force: memory buffer thresholds (load control) + disk read-ahead (streamer). */
data class BufferPolicy(
    /** Load whenever less than this is buffered. */
    val minBufferMs: Long,
    /** Stop loading at this much. */
    val maxBufferMs: Long,
    /** Buffered media needed to start (and after a seek). */
    val startPlaybackMs: Long,
    /** Needed to resume after a stall. */
    val resumeAfterRebufferMs: Long,
    /** Hard memory cap of the media buffer. */
    val targetBufferBytes: Long,
    /** Telegram download window ahead of the reader. */
    val readAheadBytes: Long,
    val prefetchAllowed: Boolean,
    val risk: RebufferRisk,
    /** Decoder struggling: seek to key frames (less decoding per seek). */
    val preferFastSeek: Boolean,
    val reason: String,
) {
    companion object {
        /** Phase 7's fixed configuration (the benchmark baseline). */
        fun static(readAheadBytes: Long = 32L * 1024 * 1024) = BufferPolicy(15_000, 50_000, 1_000, 2_500, 32L * 1024 * 1024, readAheadBytes, false, RebufferRisk.NONE, false, "static")
    }
}

data class BufferTier(val minBufferMs: Long, val maxBufferMs: Long, val readAheadSeconds: Long)

data class BufferConfig(
    // Disk read-ahead (third value, seconds) is the outage cushion: the player refills its RAM
    // buffer from it while the network is down. Generous on unmetered networks; metered networks
    // are capped below.
    val good: BufferTier = BufferTier(15_000, 50_000, 120),
    val fair: BufferTier = BufferTier(25_000, 90_000, 180),
    val poor: BufferTier = BufferTier(40_000, 150_000, 300),
    val unknown: BufferTier = BufferTier(20_000, 60_000, 120),
    /** Metered networks: never read further ahead than this (the user may stop watching)… */
    val meteredReadAheadSeconds: Long = 90,
    /** …and only this much while paused. */
    val pausedMeteredReadAheadSeconds: Long = 30,
    val minReadAheadBytes: Long = 4L * MB,
    val maxReadAheadBytes: Long = 128L * MB,
    val unknownBitrateReadAheadBytes: Long = 32L * MB,
    val maxStartMs: Long = 30_000,
    val maxResumeMs: Long = 20_000,
    /** A better network must hold this long before buffering relaxes (worse applies at once). */
    val upgradeHoldMs: Long = 10_000,
    /** Window for the buffer-level trend. */
    val trendWindowMs: Long = 8_000,
    /** Stalls within this window raise the resume threshold. */
    val rebufferMemoryMs: Long = 120_000,
    val allowMeteredPrefetch: Boolean = false,
    /** Uninterrupted playback the start buffer should buy when the network is slower than the video. */
    val startCoverMs: Long = 300_000,
    /** …and the resume buffer after a stall (×1.5 per recent stall). */
    val resumeCoverMs: Long = 120_000,
    /** Seeks within this window count towards exploration… */
    val exploreWindowMs: Long = 60_000,
    /** …which ends after this long without a seek. */
    val exploreSettleMs: Long = 30_000,
    val exploreMaxBufferMs: Long = 15_000,
    /** A raised rebuffer risk is kept at least this long. */
    val riskHoldMs: Long = 10_000,
) {
    private companion object { const val MB = 1024L * 1024L }
}

/**
 * Decides the [BufferPolicy] from network, bitrate, device, buffer level/trend and playback phase.
 * Pure function of its inputs plus a little history (quality hysteresis, buffer trend, recent
 * stalls); called on every playback sample.
 */
class BufferManager(
    private val device: DeviceProfile = DeviceProfile(),
    private val config: BufferConfig = BufferConfig(),
) {
    private val trend = BufferTrend(config.trendWindowMs)
    private val rebuffers = ArrayDeque<Long>()
    private val seeks = ArrayDeque<Long>()
    private var lastSeekAt: Long? = null
    private var heldRisk = RebufferRisk.NONE
    private var heldRiskAt = 0L
    private var lastPhase = PlaybackPhase.IDLE
    private var effectiveQuality = NetworkQuality.UNKNOWN
    private var betterSince: Long? = null
    @Volatile var policy: BufferPolicy = BufferPolicy.static(config.unknownBitrateReadAheadBytes)
        private set
    var trendMsPerSecond: Double? = null; private set
    var timeToEmptyMs: Long? = null; private set

    @Synchronized fun update(sample: PlaybackSample, network: NetworkState): BufferPolicy {
        val now = sample.nowMs
        trackPhase(sample)
        updateQuality(network.quality, now)

        val bitrate = sample.mediaBytesPerSecond?.takeIf { it > 0 }
        val headroom = if (bitrate != null && network.bytesPerSecond != null) network.bytesPerSecond.toDouble() / bitrate else null
        val reasons = mutableListOf<String>()

        // 1. Tier from (smoothed) network quality, tightened by the throughput/bitrate headroom.
        var tier = tierFor(effectiveQuality)
        if (headroom != null) {
            if (headroom < 1.2) { tier = config.poor; reasons += "headroom ${"%.2f".format(headroom)} < 1.2" }
            else if (headroom < 2.0 && tier == config.good) { tier = config.fair; reasons += "headroom ${"%.2f".format(headroom)} < 2" }
        }
        reasons += "network ${effectiveQuality}"

        // 2. Memory bound: never keep more in RAM than the device budget allows.
        val budget = device.memoryBufferBudgetBytes
        var maxBuffer = tier.maxBufferMs
        var minBuffer = tier.minBufferMs
        if (bitrate != null) {
            val cap = budget * 1000 / bitrate
            if (cap < maxBuffer) { maxBuffer = maxOf(cap, 5_000); reasons += "memory cap ${maxBuffer / 1000}s" }
            if (minBuffer > maxBuffer - 2_000) minBuffer = (maxBuffer - 2_000).coerceAtLeast(2_000)
        }

        // 3. Start / resume thresholds. With the network slower than the video (headroom h < 1) the
        //    buffer drains at (1 - h) per second of playback, so B = (1 - h) · T buys T seconds of
        //    uninterrupted playback. Aim for T = 5 min at start and 2 min after each stall (longer
        //    after repeated stalls): few, short stalls instead of many, or one huge wait.
        val remaining = sample.remainingMs ?: 600_000
        val recentStalls = rebuffers.size
        val startCap = minOf(config.maxStartMs, maxBuffer * 8 / 10)
        val start = when {
            headroom == null -> 2_000L
            headroom >= 3 -> 1_000L
            headroom >= 1.5 -> 2_000L
            headroom >= 1.1 -> 4_000L
            headroom >= 1.0 -> 6_000L // thin margin: jitter alone can stall
            else -> ((1 - headroom) * minOf(remaining, config.startCoverMs)).toLong().coerceIn(6_000, startCap)
        }.coerceAtMost(maxBuffer)
        val deficit = if (headroom == null) null else (1 - headroom).coerceAtLeast(if (headroom < 1.1) 0.05 else 0.0)
        val cover = config.resumeCoverMs * 1.5.pow(recentStalls)
        val resume = (if (deficit == null || deficit == 0.0) maxOf(start * 2, 2_500) * 1.5.pow(recentStalls) else deficit * minOf(remaining.toDouble(), cover))
            .toLong().coerceIn(2_500, minOf(config.maxResumeMs, maxBuffer))
        if (recentStalls > 0) reasons += "$recentStalls recent stalls"

        // 3b. Exploration: several seeks in a short time = the user is looking for something. Keep the
        //     buffer and the download window small until they settle (saves the bandwidth that a full
        //     buffer at each abandoned position would cost).
        val exploring = seeks.size >= 2 && sample.phase != PlaybackPhase.PAUSED && now - (lastSeekAt ?: now) < config.exploreSettleMs
        if (exploring) {
            maxBuffer = minOf(maxBuffer, config.exploreMaxBufferMs)
            minBuffer = minOf(minBuffer, maxBuffer - 2_000)
            reasons += "exploring (${seeks.size} seeks)"
        }

        // 4. Rebuffer prediction from the buffer trend.
        val slope = trend.slope()
        trendMsPerSecond = slope?.times(1000)
        val ahead = sample.bufferedAheadMs
        timeToEmptyMs = if (slope != null && slope < 0) (ahead / -slope).toLong() else null
        val left = sample.remainingMs
        val nearEnd = left != null && ahead >= left - 500
        val measured = when {
            sample.phase != PlaybackPhase.PLAYING || nearEnd -> RebufferRisk.NONE
            slope != null && slope < 0 && (timeToEmptyMs!! < 8_000 || ahead < resume) -> RebufferRisk.HIGH
            // Falling at more than half of real time = the network delivers under half the bitrate.
            slope != null && (slope < -0.5 || (slope < -0.2 && timeToEmptyMs!! < 60_000)) -> RebufferRisk.ELEVATED
            ahead < start && sample.phase == PlaybackPhase.PLAYING && effectiveQuality <= NetworkQuality.POOR -> RebufferRisk.ELEVATED
            else -> RebufferRisk.NONE
        }
        // Hold a raised risk for a while: the trend is noisy, flapping would toggle the window size.
        val risk = if (measured >= heldRisk || now - heldRiskAt >= config.riskHoldMs || sample.phase != PlaybackPhase.PLAYING) {
            if (measured != heldRisk) { heldRisk = measured; heldRiskAt = now }
            measured
        } else heldRisk
        if (risk != RebufferRisk.NONE) {
            // Keep loading continuously (min = max) and widen the download window.
            minBuffer = maxBuffer
            reasons += "risk $risk (trend ${"%.2f".format(slope ?: 0.0)}, empty in ${timeToEmptyMs?.div(1000)}s)"
        }

        // 5. Disk read-ahead: playback time by tier, limited on metered networks, never less than the RAM buffer.
        var seconds = tier.readAheadSeconds
        if (network.isMetered && risk == RebufferRisk.NONE) {
            seconds = minOf(seconds, if (sample.phase == PlaybackPhase.PAUSED) config.pausedMeteredReadAheadSeconds else config.meteredReadAheadSeconds)
            reasons += "metered"
        }
        if (risk != RebufferRisk.NONE) seconds *= 2
        if (exploring) seconds = minOf(seconds, config.exploreMaxBufferMs / 1000)
        val readAhead = if (bitrate == null) config.unknownBitrateReadAheadBytes else
            maxOf(bitrate * seconds, bitrate * maxBuffer / 1000 * 12 / 10).coerceIn(config.minReadAheadBytes, config.maxReadAheadBytes)

        // 6. Prefetch only with bandwidth to spare.
        val prefetch = risk == RebufferRisk.NONE && !network.isOffline &&
            (effectiveQuality == NetworkQuality.GOOD || (effectiveQuality == NetworkQuality.FAIR && (headroom ?: 0.0) >= 2.0)) &&
            (headroom == null || headroom >= 1.5) &&
            (!network.isMetered || config.allowMeteredPrefetch) &&
            sample.phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED)

        val fastSeek = sample.droppedFramesPerSecond > 3.0 || (device.lowRam && device.cpuCores <= 4)
        policy = BufferPolicy(minBuffer, maxBuffer, start, resume, budget, readAhead, prefetch, risk, fastSeek, reasons.joinToString("; "))
        return policy
    }

    private fun trackPhase(s: PlaybackSample) {
        val now = s.nowMs
        if (lastPhase == PlaybackPhase.PLAYING && s.phase == PlaybackPhase.BUFFERING) rebuffers.addLast(now)
        while (rebuffers.isNotEmpty() && now - rebuffers.first() > config.rebufferMemoryMs) rebuffers.removeFirst()
        if (s.phase == PlaybackPhase.SEEKING && lastPhase != PlaybackPhase.SEEKING) { seeks.addLast(now); lastSeekAt = now }
        while (seeks.isNotEmpty() && now - seeks.first() > config.exploreWindowMs) seeks.removeFirst()
        // The trend is only meaningful during continuous playback while the player is loading:
        // a full buffer drains at playback speed on purpose (load control stopped fetching).
        if (s.phase == PlaybackPhase.PLAYING && s.loading) trend.add(now, s.bufferedAheadMs) else trend.clear()
        lastPhase = s.phase
    }

    private fun updateQuality(q: NetworkQuality, now: Long) {
        if (rank(q) <= rank(effectiveQuality)) { effectiveQuality = q; betterSince = null; return }
        val since = betterSince ?: now.also { betterSince = it }
        if (now - since >= config.upgradeHoldMs || effectiveQuality == NetworkQuality.UNKNOWN) { effectiveQuality = q; betterSince = null }
    }

    private fun rank(q: NetworkQuality) = when (q) {
        NetworkQuality.OFFLINE -> 0; NetworkQuality.POOR -> 1; NetworkQuality.FAIR, NetworkQuality.UNKNOWN -> 2; NetworkQuality.GOOD -> 3
    }

    private fun tierFor(q: NetworkQuality) = when (q) {
        NetworkQuality.GOOD -> config.good
        NetworkQuality.FAIR -> config.fair
        NetworkQuality.POOR, NetworkQuality.OFFLINE -> config.poor
        NetworkQuality.UNKNOWN -> config.unknown
    }
}

/** Least-squares slope of the buffered-ahead level over a sliding time window (ms of buffer per ms). */
class BufferTrend(private val windowMs: Long) {
    private val points = ArrayDeque<Pair<Long, Long>>()

    fun add(t: Long, aheadMs: Long) {
        points.addLast(t to aheadMs)
        while (points.size > 2 && t - points.first().first > windowMs) points.removeFirst()
    }

    fun clear() = points.clear()

    /** Null until the window is at least half full. */
    fun slope(): Double? {
        if (points.size < 3 || points.last().first - points.first().first < windowMs / 2) return null
        val n = points.size
        val mt = points.sumOf { it.first.toDouble() } / n
        val mv = points.sumOf { it.second.toDouble() } / n
        var num = 0.0; var den = 0.0
        for ((t, v) in points) { num += (t - mt) * (v - mv); den += (t - mt) * (t - mt) }
        return if (den == 0.0) null else num / den
    }
}
