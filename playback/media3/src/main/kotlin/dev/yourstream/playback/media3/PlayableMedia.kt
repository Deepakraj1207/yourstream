package dev.yourstream.playback.media3

import androidx.media3.common.MediaItem
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.stream.StreamHints

/**
 * A file that can be played right now: the library entry it belongs to plus the Telegram file
 * of the current session. Built by a [MediaSourceResolver] at play time, never stored.
 */
data class PlayableMedia(
    val title: String,
    val fileName: String,
    /** Telegram file id of the current session. */
    val fileId: Int,
    val remoteUniqueId: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val alreadyDownloaded: Boolean,
    /** Library target and file, for watch progress (null for ad-hoc files). */
    val target: PlaybackTarget? = null,
    val mediaId: Long? = null,
    /** External subtitle files (the user's Telegram subtitle files / local files), loaded before play. */
    val subtitles: List<SideloadedSubtitle> = emptyList(),
    /** What the library knows around the file, for language detection. */
    val trackContext: dev.yourstream.playback.tracks.FileContext? = null,
    /**
     * A file on this device: its `content://` address, played directly by Media3 (no Telegram
     * streaming; [fileId] is 0). Null for Telegram files.
     */
    val localUri: String? = null,
) {
    val isLocal: Boolean get() = localUri != null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun mediaItem(): MediaItem = MediaItem.Builder()
        .setUri(localUri?.let(android.net.Uri::parse) ?: TelegramMediaUri(fileId, StreamHints(sizeBytes.takeIf { it > 0 }, durationMs)).toUri())
        .setMediaId(mediaId?.let { "media:$it" } ?: "tg:$remoteUniqueId")
        .setCustomCacheKey(if (localUri != null) "local:$remoteUniqueId" else "tg:$remoteUniqueId")
        .setSubtitleConfigurations(subtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(java.io.File(s.localPath)))
                .setId(s.id)
                .setMimeType(s.mimeType)
                .setLanguage(s.language)
                .setLabel(s.label)
                .setSelectionFlags(if (s.forced) androidx.media3.common.C.SELECTION_FLAG_FORCED else 0)
                .setRoleFlags(if (s.sdh) androidx.media3.common.C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
                .build()
        })
        .build()
}

/**
 * Library → playable Telegram file. Picks the version (file) to play, refreshes the session-local
 * Telegram file id from the message, and checks the file can still be obtained.
 */
interface MediaSourceResolver {
    suspend fun resolve(target: PlaybackTarget, mediaId: Long? = null): AppResult<PlayableMedia>

    /** The best playable version other than [excludedMediaIds] (recovery after a file failed). */
    suspend fun resolveExcluding(target: PlaybackTarget, excludedMediaIds: Set<Long>): AppResult<PlayableMedia> =
        AppResult.Err(dev.yourstream.core.common.AppError.NotFound("No other version"))
}

/** Where playback reports watch progress (the library's watch history in the app). */
fun interface PlaybackProgressSink {
    suspend fun save(target: PlaybackTarget, mediaId: Long?, positionMs: Long, durationMs: Long)
}
