package dev.yourstream.playback.media3

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.telegram.api.TelegramFileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saving a Telegram video on the device to watch without a connection ("Download" on a title's
 * version). TDLib downloads the whole file into its own cache, at a low priority so playback
 * always comes first; the library marks it "On this device" when TDLib reports it complete.
 *
 * Downloads the user asked for are remembered ([isKept], by Telegram's stable unique id) so the
 * stream cache never trims them. Downloads run while the app runs; one interrupted (the app
 * closed) continues from where it stopped when "Download" is tapped again.
 *
 * A movie in raw pieces downloads every piece; its progress is the bytes of all pieces together,
 * and cancel / remove act on every piece.
 */
@Singleton
class VideoDownloads @Inject constructor(
    @ApplicationContext context: Context,
    private val resolver: MediaSourceResolver,
    private val files: TelegramFileRepository,
    @PlaybackScope private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** Downloads running now: media id → fraction done. */
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()
    private val watchers = ConcurrentHashMap<Long, Job>()

    fun isKept(remoteUniqueId: String): Boolean = prefs.getBoolean(remoteUniqueId, false)

    suspend fun start(target: PlaybackTarget, mediaId: Long): AppResult<Unit> {
        val media = when (val r = telegramFile(target, mediaId)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return r
        }
        prefs.edit().apply { keys(media).forEach { putBoolean(it, true) } }.apply()
        if (media.alreadyDownloaded) return AppResult.Ok(Unit)
        val done = HashMap<Int, Long>()
        val finished = HashSet<Int>()
        for (id in media.fileIds) {
            val started = files.download(id, priority = PRIORITY, offset = 0, limit = 0)
            if (started is AppResult.Err) return started
            val f = (started as AppResult.Ok).value
            done[id] = f.downloadedSize
            if (f.isDownloadingCompleted) finished += id
        }
        _progress.update { it + (mediaId to fraction(media.sizeBytes, done.values.sum())) }
        watchers.remove(mediaId)?.cancel()
        watchers[mediaId] = scope.launch {
            kotlinx.coroutines.flow.merge(*media.fileIds.map { files.observeFile(it) }.toTypedArray()).collect { f ->
                done[f.id] = f.downloadedSize
                if (f.isDownloadingCompleted || !f.isDownloadingActive) finished += f.id
                if (finished.containsAll(media.fileIds)) {
                    _progress.update { it - mediaId }
                    watchers.remove(mediaId)?.cancel()
                } else {
                    _progress.update { it + (mediaId to fraction(media.sizeBytes, done.values.sum())) }
                }
            }
        }
        return AppResult.Ok(Unit)
    }

    /** What is remembered as kept: the file, or every piece of a split file. */
    private fun keys(media: PlayableMedia): List<String> =
        if (media.isSplit) media.parts.map { it.remoteUniqueId } else listOf(media.remoteUniqueId)

    private suspend fun eachFile(media: PlayableMedia, action: suspend (Int) -> AppResult<Unit>): AppResult<Unit> =
        media.fileIds.map { action(it) }.firstOrNull { it is AppResult.Err } ?: AppResult.Ok(Unit)

    suspend fun cancel(target: PlaybackTarget, mediaId: Long): AppResult<Unit> {
        val media = (telegramFile(target, mediaId) as? AppResult.Ok)?.value ?: return AppResult.Err(AppError.NotFound("not a Telegram file"))
        watchers.remove(mediaId)?.cancel()
        _progress.update { it - mediaId }
        prefs.edit().apply { keys(media).forEach { remove(it) } }.apply()
        return eachFile(media) { files.cancelDownload(it) }
    }

    /** Frees the space: the downloaded copy is deleted (the video stays in Telegram). */
    suspend fun remove(target: PlaybackTarget, mediaId: Long): AppResult<Unit> {
        val media = (telegramFile(target, mediaId) as? AppResult.Ok)?.value ?: return AppResult.Err(AppError.NotFound("not a Telegram file"))
        watchers.remove(mediaId)?.cancel()
        _progress.update { it - mediaId }
        prefs.edit().apply { keys(media).forEach { remove(it) } }.apply()
        return eachFile(media) { files.deleteLocalCopy(it) }
    }

    private suspend fun telegramFile(target: PlaybackTarget, mediaId: Long): AppResult<PlayableMedia> =
        when (val r = resolver.resolve(target, mediaId)) {
            is AppResult.Ok -> if (r.value.isLocal) AppResult.Err(AppError.InvalidInput("LOCAL", "Already on this device")) else r
            is AppResult.Err -> r
        }

    companion object {
        /** The preferences that list kept downloads (account data: cleared on sign-out). */
        const val PREFS = "downloads"
        /** Below playback's priorities (TDLib: 1..32, higher first). */
        private const val PRIORITY = 1

        fun fraction(size: Long, done: Long): Float = if (size <= 0) 0f else (done.toFloat() / size).coerceIn(0f, 1f)
    }
}
