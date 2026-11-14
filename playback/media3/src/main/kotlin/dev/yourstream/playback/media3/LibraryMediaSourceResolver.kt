package dev.yourstream.playback.media3

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MediaVersion
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.model.MessageLookup
import dev.yourstream.telegram.api.model.TelegramFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a library target to the Telegram file to stream.
 *
 * File availability is checked here, before the player starts:
 *  - the message is looked up again (file ids are only valid for the current Telegram session,
 *    and the message may have been deleted since indexing);
 *  - offline, the stored file id is used if Telegram still knows it with the same unique id;
 *  - a file Telegram cannot download (and that is not on the device) is reported unavailable.
 */
@Singleton
class LibraryMediaSourceResolver @Inject constructor(
    private val media: MediaRepository,
    private val episodes: EpisodeRepository,
    private val db: YourStreamDatabase,
    private val messages: TelegramMessageRepository,
    private val files: TelegramFileRepository,
    private val localFiles: LocalFileCheck,
) : MediaSourceResolver {

    override suspend fun resolve(target: PlaybackTarget, mediaId: Long?): AppResult<PlayableMedia> {
        val versions = target.episodeId?.let { episodes.versions(it) } ?: media.versions(target)
        if (mediaId != null) {
            val version = versions.firstOrNull { it.mediaId == mediaId } ?: return AppResult.Err(AppError.NotFound("No playable file for this title"))
            return resolveVersion(target, version)
        }
        return firstPlayable(target, versions, emptySet())
    }

    override suspend fun resolveExcluding(target: PlaybackTarget, excludedMediaIds: Set<Long>): AppResult<PlayableMedia> {
        val versions = target.episodeId?.let { episodes.versions(it) } ?: media.versions(target)
        return firstPlayable(target, versions, excludedMediaIds)
    }

    /**
     * Tries versions best first: a file whose message was deleted since indexing, or that
     * Telegram can no longer download, falls through to the next version instead of failing.
     */
    private suspend fun firstPlayable(target: PlaybackTarget, versions: List<MediaVersion>, excluded: Set<Long>): AppResult<PlayableMedia> {
        val candidates = rank(versions).filter { it.mediaId !in excluded }
        if (candidates.isEmpty()) return AppResult.Err(AppError.NotFound(if (excluded.isEmpty()) "No playable file for this title" else "No other version of this title"))
        var last: AppResult.Err? = null
        for (v in candidates) {
            when (val r = resolveVersion(target, v)) {
                is AppResult.Ok -> return r
                is AppResult.Err -> {
                    last = r
                    // Offline / Telegram unreachable: other versions will fail the same way.
                    if (r.error !is AppError.NotFound) return r
                }
            }
        }
        return last!!
    }

    private suspend fun resolveVersion(target: PlaybackTarget, version: MediaVersion): AppResult<PlayableMedia> {
        version.locator?.let { return resolveLocal(target, version, it) }
        if (version.isDeleted) return AppResult.Err(AppError.NotFound("The Telegram message of ${version.fileName} was deleted"))

        val (file, mime) = when (val r = currentFile(version)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return r
        }
        if (!file.isDownloadingCompleted && !file.canBeDownloaded) {
            return AppResult.Err(AppError.NotFound("Telegram can no longer download ${version.fileName}"))
        }
        val title = media.getItem(target.itemId)?.summary?.title ?: version.fileName
        return AppResult.Ok(
            PlayableMedia(
                title = title, fileName = version.fileName, fileId = file.id, remoteUniqueId = file.remoteUniqueId,
                sizeBytes = file.size.takeIf { it > 0 } ?: version.sizeBytes, durationMs = version.durationSeconds?.let { it * 1000L },
                mimeType = mime, width = null, height = version.resolution, alreadyDownloaded = file.isDownloadingCompleted,
                target = target, mediaId = version.mediaId,
            ),
        )
    }

    /** A file on this device: played from its address if it can still be read (it may have been deleted or moved since the last scan). */
    private suspend fun resolveLocal(target: PlaybackTarget, version: MediaVersion, uri: String): AppResult<PlayableMedia> {
        if (version.isDeleted || !localFiles.readable(uri)) {
            return AppResult.Err(AppError.NotFound("${version.fileName} is no longer on this device"))
        }
        val title = media.getItem(target.itemId)?.summary?.title ?: version.fileName
        return AppResult.Ok(
            PlayableMedia(
                title = title, fileName = version.fileName, fileId = 0, remoteUniqueId = "media-${version.mediaId}",
                sizeBytes = version.sizeBytes, durationMs = version.durationSeconds?.let { it * 1000L }, mimeType = null,
                width = null, height = version.resolution, alreadyDownloaded = true, target = target, mediaId = version.mediaId,
                localUri = uri,
            ),
        )
    }

    private suspend fun currentFile(v: MediaVersion): AppResult<Pair<TelegramFile, String?>> =
        when (val r = messages.lookupMessages(v.chatId, listOf(v.messageId))) {
            is AppResult.Ok -> when (val found = r.value.firstOrNull()) {
                is MessageLookup.Found -> AppResult.Ok(found.message.media.file to found.message.media.mimeType)
                else -> AppResult.Err(AppError.NotFound("The Telegram message of ${v.fileName} no longer exists"))
            }
            is AppResult.Err -> storedFile(v.mediaId) ?: r
        }

    /** Offline fallback: the file id saved at indexing time, if Telegram still maps it to the same file. */
    private suspend fun storedFile(mediaId: Long): AppResult<Pair<TelegramFile, String?>>? {
        val row = db.mediaDao().getWithParsed(mediaId)?.media ?: return null
        val file = (files.getFile(row.fileId) as? AppResult.Ok)?.value ?: return null
        return if (file.remoteUniqueId == row.fileUniqueId) AppResult.Ok(file to row.mimeType) else null
    }

    companion object {
        /** Best version: not deleted/excluded, an original before a duplicate, then highest resolution, then largest. */
        fun chooseVersion(versions: List<MediaVersion>): MediaVersion? = rank(versions).firstOrNull()

        /** All usable versions, best first. */
        fun rank(versions: List<MediaVersion>): List<MediaVersion> = versions
            .filter { !it.isDeleted && !it.isExcluded }
            .sortedWith(compareBy<MediaVersion> { it.isDuplicate }.thenByDescending { it.resolution ?: 0 }.thenByDescending { it.sizeBytes })
    }
}

/** Whether a local file can still be opened (a deleted file or a lost folder permission cannot). */
fun interface LocalFileCheck {
    suspend fun readable(uri: String): Boolean
}

/** Opens the file for reading and closes it at once: the only reliable check across providers. */
class AndroidLocalFileCheck @Inject constructor(@dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context) : LocalFileCheck {
    override suspend fun readable(uri: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { context.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")?.use { true } ?: false }.getOrDefault(false)
    }
}
