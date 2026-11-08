package dev.yourstream.playback.stream

/**
 * Tuning of Telegram streaming. Defaults target video playback on mobile networks.
 *
 * The download is a sliding window in front of the reader: Telegram is asked for
 * `[first missing byte, reader + readAhead)` only. Nothing outside the window is requested, so a
 * jump from 00:05 to 01:20:00 never downloads the hour in between, and a paused player stops
 * downloading once the window is full.
 */
data class StreamingConfig(
    /** Window used when the bitrate is unknown. */
    val defaultReadAheadBytes: Long = 32L * MB,
    /** With a known bitrate the window covers this much playback time… */
    val readAheadSeconds: Int = 90,
    /** …but never less / more than this. */
    val minReadAheadBytes: Long = 8L * MB,
    val maxReadAheadBytes: Long = 96L * MB,
    /** The window is extended when less than this fraction of it is still requested ahead of the reader. */
    val extendAtFraction: Double = 0.5,
    /**
     * If Telegram's download head is further than this before the byte the reader needs, the
     * download is re-pointed instead of letting it fetch the gap (a seek inside the window).
     */
    val maxGapBytes: Long = 2L * MB,
    /** Telegram download priority (1..32). Playback must win over background downloads. */
    val priority: Int = 32,
    /** No new data at the read position for this long = stalled (network down, server not answering). */
    val stallTimeoutMs: Long = 20_000,
    /** Fallback poll while waiting for data, in case a progress update is missed. */
    val pollIntervalMs: Long = 150,
    /** Max bytes per readPart call when the local file cannot be read directly. */
    val maxReadPartBytes: Int = 512 * 1024,
    /** Read the partially downloaded file directly (fast); readPart is the fallback. */
    val directFileReads: Boolean = true,
) {
    fun readAheadFor(hints: StreamHints): Long {
        val bitrate = hints.bytesPerSecond ?: return defaultReadAheadBytes
        return (bitrate * readAheadSeconds).coerceIn(minReadAheadBytes, maxReadAheadBytes)
    }

    companion object {
        const val MB = 1024L * 1024L
    }
}

/** What the caller knows about the media (all optional). */
data class StreamHints(val sizeBytes: Long? = null, val durationMs: Long? = null) {
    val bytesPerSecond: Long?
        get() = if (sizeBytes != null && sizeBytes > 0 && durationMs != null && durationMs > 0) sizeBytes * 1000 / durationMs else null
}
