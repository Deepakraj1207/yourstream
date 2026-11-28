package dev.yourstream.desktop.playback

import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.telegram.api.TelegramFileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Makes Play start at once: while a title's page is open, the start of its file and its end (where
 * MKV and many MP4 files keep the index VLC reads before the first picture) are downloaded in the
 * background, a few MB at low priority. When Play is pressed, VLC's first reads come from disk
 * instead of each waiting for a Telegram round trip. Opening another title moves on to that one.
 */
class StartWarmer(
    private val files: TelegramFileRepository,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val headBytes: Long = 6L shl 20,
    private val tailBytes: Long = 2L shl 20,
) {
    private var job: Job? = null

    @Synchronized
    fun warm(fileId: Int, size: Long) {
        job?.cancel()
        // Data saver: nothing is fetched before Play is pressed.
        if (size <= 0 || dev.yourstream.playback.stream.DataSaver.active) return
        job = scope.launch {
            delay(400) // only for a page that stays open, not one passed on the way
            fetch(fileId, 0, minOf(headBytes, size))
            if (size > headBytes + tailBytes) fetch(fileId, size - tailBytes, tailBytes)
        }
    }

    /** Playback starts: its own download takes over. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun fetch(fileId: Int, offset: Long, length: Long) {
        if (have(fileId, offset) >= length) return
        if (files.download(fileId, priority = PRIORITY, offset = offset, limit = length) is AppResult.Err) return
        val done = withTimeoutOrNull(30_000) {
            while (have(fileId, offset) < length) delay(150)
            true
        }
        if (done == null) logger.i("Warmer", "part of file $fileId not ready in time")
    }

    private suspend fun have(fileId: Int, offset: Long): Long =
        (files.downloadedPrefixSize(fileId, offset) as? AppResult.Ok)?.value ?: 0

    private companion object {
        /** Below playback (32): never in the way of what is playing. */
        const val PRIORITY = 4
    }
}
