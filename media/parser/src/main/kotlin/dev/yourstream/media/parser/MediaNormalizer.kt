package dev.yourstream.media.parser

import dev.yourstream.media.api.model.DownloadState
import dev.yourstream.media.api.model.MediaType
import dev.yourstream.media.api.model.ParsedMedia
import dev.yourstream.media.api.model.SourceContentType
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.telegram.api.model.TelegramMediaMessage

/** Which kinds of files go into the index. */
data class IndexingPolicy(
    val includeArchives: Boolean = false,
    val includeOther: Boolean = false,
    /** Telegram "audio" messages are mostly music; still indexed by default. */
    val includeAudio: Boolean = true,
) {
    fun accepts(type: MediaType): Boolean = when (type) {
        MediaType.VIDEO, MediaType.SUBTITLE -> true
        MediaType.AUDIO -> includeAudio
        MediaType.ARCHIVE -> includeArchives
        MediaType.OTHER -> includeOther
    }
}

/** Everything the index stores about one message's file, before it gets a database id. */
data class NormalizedMedia(
    val chatId: Long,
    val messageId: Long,
    val albumId: Long,
    val messageDate: Long,
    val editDate: Long,
    val fileId: Int,
    val fileUniqueId: String,
    val remoteFileId: String,
    /** The Telegram file name exactly as received (may be empty). Never rewritten. */
    val fileName: String,
    val mimeType: String,
    val extension: String?,
    val sizeBytes: Long,
    val durationSeconds: Int?,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodecs: List<String>,
    val supportsStreaming: Boolean,
    val mediaType: MediaType,
    val contentType: SourceContentType,
    val caption: String,
    val audioTitle: String?,
    val audioPerformer: String?,
    val downloadState: DownloadState,
    val downloadedBytes: Long,
    val localPath: String?,
    /** Normalised title words for full-text search. */
    val searchTitle: String,
    /** size + normalised name: identifies re-uploads of the same file (null for tiny files). */
    val fingerprint: String?,
    val parsed: ParsedMedia,
)

/** Media extractor + normaliser: Telegram message → [NormalizedMedia], or null if not indexed. */
class MediaNormalizer(private val policy: IndexingPolicy = IndexingPolicy()) {

    /**
     * [chatTitle] is a last-resort hint for the title when both file name and caption are
     * generic. The Telegram file name is stored exactly as received, even when empty.
     */
    fun normalize(message: TelegramMediaMessage, chatTitle: String? = null, albumCaption: String? = null): NormalizedMedia? {
        val media = message.media
        // Telegram stores an album's caption on one of its files: the others use it too.
        val caption = message.caption.ifBlank { albumCaption.orEmpty() }
        val file = media.file
        val originalName = media.fileName
        val nameForAnalysis = originalName.ifBlank { fallbackName(message) }
        val type = MediaClassifier.classify(nameForAnalysis, media.mimeType, message.contentType)
        if (!policy.accepts(type)) return null

        val parts = MediaClassifier.splitName(originalName)
        val parsed = ParsedMediaMapper.parse(originalName, caption, chatTitle)
        val size = if (file.size > 0) file.size else file.expectedSize

        return NormalizedMedia(
            chatId = message.chatId,
            messageId = message.messageId,
            albumId = message.albumId,
            messageDate = message.date,
            editDate = message.editDate,
            fileId = file.id,
            fileUniqueId = file.remoteUniqueId,
            remoteFileId = file.remoteId,
            fileName = originalName,
            mimeType = media.mimeType,
            extension = parts.extension,
            sizeBytes = size,
            durationSeconds = media.durationSeconds,
            width = media.width,
            height = media.height,
            videoCodec = media.codec?.let(::normalizeCodec) ?: parsed.videoCodec,
            audioCodecs = parsed.audioCodecs,
            supportsStreaming = media.supportsStreaming,
            mediaType = type,
            contentType = when (message.contentType) {
                TelegramMediaContentType.VIDEO -> SourceContentType.VIDEO
                TelegramMediaContentType.DOCUMENT -> SourceContentType.DOCUMENT
                TelegramMediaContentType.AUDIO -> SourceContentType.AUDIO
            },
            caption = caption,
            audioTitle = media.title,
            audioPerformer = media.performer,
            downloadState = when {
                file.isDownloadingCompleted -> DownloadState.COMPLETE
                file.downloadedSize > 0 -> DownloadState.PARTIAL
                else -> DownloadState.NOT_DOWNLOADED
            },
            downloadedBytes = file.downloadedSize,
            localPath = file.localPath,
            searchTitle = searchTitle(parsed, media.title, media.performer),
            fingerprint = fingerprint(parts.baseName, parts.extension, parts.splitPart, size),
            parsed = parsed,
        )
    }

    private fun fallbackName(message: TelegramMediaMessage): String {
        val ext = when (message.contentType) {
            TelegramMediaContentType.VIDEO -> "mp4"
            TelegramMediaContentType.AUDIO -> "mp3"
            TelegramMediaContentType.DOCUMENT -> "bin"
        }
        return "${message.chatId}_${message.messageId}.$ext"
    }

    companion object {
        /** Words FTS indexes next to the file name: titles, alternative title, audio tags. */
        fun searchTitle(parsed: ParsedMedia, audioTitle: String?, performer: String?): String =
            SearchText.normalize(listOfNotNull(parsed.title, parsed.alternativeTitle, audioTitle, performer).joinToString(" "))

        /** Files smaller than this are too likely to collide by name+size by accident. */
        const val MIN_FINGERPRINT_BYTES: Long = 1L * 1024 * 1024

        fun fingerprint(baseName: String, extension: String?, splitPart: Int?, size: Long): String? {
            if (size < MIN_FINGERPRINT_BYTES) return null
            val name = SearchText.normalize(baseName)
            if (name.isEmpty()) return null
            return "$size:$name:${extension.orEmpty()}:${splitPart ?: 0}"
        }

        fun normalizeCodec(codec: String): String = when (codec.lowercase()) {
            "h264", "avc", "avc1" -> "H.264"
            "h265", "hevc", "hvc1", "hev1" -> "H.265"
            "av1", "av01" -> "AV1"
            "vp9", "vp09" -> "VP9"
            else -> codec
        }
    }
}
