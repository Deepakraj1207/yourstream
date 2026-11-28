package dev.yourstream.playback.stream

/**
 * Data saver (Settings): stream with as little data and storage as playback allows. Telegram
 * plays files as they were uploaded (no smaller copies), so the data a video uses is the size of
 * the version played times how much of it is watched. While data saver is [active]:
 *
 *  - the smallest suitable version plays (one already on the device first; see [smallestFirst]);
 *  - Telegram is asked for less ahead of the player ([READ_AHEAD_SECONDS], [PAUSED_READ_AHEAD_SECONDS],
 *    [UNKNOWN_BITRATE_READ_AHEAD_BYTES]);
 *  - nothing is fetched in advance (the next episode, the start of a title whose page is open);
 *  - streamed parts kept on the device are trimmed above [CACHE_BUDGET_BYTES] instead of 2 GB.
 *
 * Set by each app from its settings ([mode]) and the platform ([metered]); read by playback.
 */
object DataSaver {
    enum class Mode {
        OFF,
        /** Only while the connection is metered (mobile data, a phone's hotspot). */
        MOBILE_DATA,
        ALWAYS,
    }

    @Volatile var mode: Mode = Mode.OFF

    /** Whether the current connection is metered, asked when needed (the platform sets it; unknown = not metered). */
    @Volatile var metered: () -> Boolean = { false }

    val active: Boolean get() = when (mode) {
        Mode.OFF -> false
        Mode.ALWAYS -> true
        Mode.MOBILE_DATA -> runCatching { metered() }.getOrDefault(false)
    }

    /** Disk read-ahead while playing / paused (normal: 2–5 minutes; 90 s / 30 s on mobile data). */
    const val READ_AHEAD_SECONDS = 45L
    const val PAUSED_READ_AHEAD_SECONDS = 15L
    /**
     * The player's memory buffer (normal: 50–150 s). The disk read-ahead never goes below 1.2 × this
     * buffer, so it is kept under [READ_AHEAD_SECONDS]; paused, the player still fills it (≈36 s).
     */
    const val RAM_BUFFER_SECONDS = 30L
    /** Read-ahead when the video's bitrate is not known (normal: 32 MB). */
    const val UNKNOWN_BITRATE_READ_AHEAD_BYTES = 12L * 1024 * 1024
    /** Streamed parts kept on the device (normal: 2 GB). Kept, not cleared: watching again or resuming then costs no data. */
    const val CACHE_BUDGET_BYTES = 500L * 1024 * 1024
    /** Versions below this height (samples, trailers, phone recordings) play only when nothing else is there. */
    const val MIN_HEIGHT = 480

    /**
     * Versions in data saver order: on the device first (no data at all), then the smallest file
     * of at least [MIN_HEIGHT]p (unknown height counts as enough), then smaller-than-that ones;
     * files of unknown size last. [onDevice], [height] and [size] read a version's details.
     */
    fun <T> smallestFirst(versions: List<T>, onDevice: (T) -> Boolean, height: (T) -> Int?, size: (T) -> Long): List<T> =
        versions.sortedWith(
            compareBy<T> { !onDevice(it) }
                .thenBy { (height(it) ?: MIN_HEIGHT) < MIN_HEIGHT }
                .thenBy { size(it) <= 0L }
                .thenBy { size(it) },
        )
}
