package dev.yourstream.playback.media3

enum class PlaybackStatus { IDLE, PREPARING, BUFFERING, READY, ENDED, ERROR }

enum class PlaybackErrorKind {
    /** Network down / too slow / Telegram server error. Retrying later can help. */
    NETWORK,
    /** The Telegram file or message is gone or cannot be downloaded. */
    FILE_UNAVAILABLE,
    /** Container or codec not supported on this device. */
    UNSUPPORTED_FORMAT,
    DECODER,
    UNKNOWN,
}

data class PlaybackError(val kind: PlaybackErrorKind, val message: String, val retryable: Boolean, val code: Int? = null)

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    /** Null until known. */
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0,
    val media: PlayableMedia? = null,
    val error: PlaybackError? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val seeking: Boolean = false,
)

/** What the test player shows and the tests assert on. Times are wall-clock milliseconds. */
data class PlaybackMetrics(
    /** prepare() → first READY. */
    val startupMs: Long? = null,
    /** prepare() → first video frame rendered. */
    val firstFrameMs: Long? = null,
    /** Stalls during playback (READY → BUFFERING without a seek) and their total time. */
    val rebufferCount: Int = 0,
    val rebufferMs: Long = 0,
    val seekCount: Int = 0,
    /** seek → READY, per seek. */
    val seekLatenciesMs: List<Long> = emptyList(),
    val retries: Int = 0,
    val peakJavaHeapBytes: Long = 0,
    val peakNativeHeapBytes: Long = 0,
    /** Bytes handed to the player / downloaded from Telegram for this file during this playback. */
    val bytesServed: Long = 0,
    val downloadedBytes: Long = 0,
    val downloadRequests: Int = 0,
    /** Time the loader spent waiting for Telegram data. */
    val dataWaitMs: Long = 0,
) {
    val lastSeekLatencyMs: Long? get() = seekLatenciesMs.lastOrNull()
}

data class PlaybackConfig(
    /** Media3 buffer: keep 15–50 s decoded-ready data in memory, start after 1 s, resume after 2.5 s. */
    val minBufferMs: Int = 15_000,
    val maxBufferMs: Int = 50_000,
    val bufferForPlaybackMs: Int = 1_000,
    val bufferForPlaybackAfterRebufferMs: Int = 2_500,
    /** Hard cap on buffer memory, whatever the bitrate (4K files would otherwise use >100 MB). */
    val targetBufferBytes: Int = 32 * 1024 * 1024,
    val progressSaveIntervalMs: Long = 10_000,
    /** Load retries (stall/network) before the error reaches the player, with exponential backoff. */
    val loadRetries: Int = 5,
    val retryBaseDelayMs: Long = 1_000,
    val retryMaxDelayMs: Long = 8_000,
    /** Retry automatically when Telegram reconnects after a network error. */
    val autoRetryOnReconnect: Boolean = true,
    val maxAutoRetries: Int = 3,
    val metricsSampleIntervalMs: Long = 500,
)
