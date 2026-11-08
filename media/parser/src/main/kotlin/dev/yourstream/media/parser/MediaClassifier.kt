package dev.yourstream.media.parser

import dev.yourstream.media.api.model.MediaType
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import dev.yourstream.media.filename.FileExtensions
import java.util.Locale

/** Decides what a file is. Telegram's MIME type for documents is often just octet-stream. */
object MediaClassifier {

    val VIDEO_EXTENSIONS: Set<String> = FileExtensions.VIDEO
    val AUDIO_EXTENSIONS: Set<String> = FileExtensions.AUDIO
    val SUBTITLE_EXTENSIONS: Set<String> = FileExtensions.SUBTITLE
    val ARCHIVE_EXTENSIONS: Set<String> = FileExtensions.ARCHIVE

    private val SUBTITLE_MIMES = setOf("application/x-subrip", "text/vtt", "text/x-ssa", "text/x-ass", "application/x-ass")

    data class FileNameParts(val baseName: String, val extension: String?, val splitPart: Int?)

    fun splitName(fileName: String): FileNameParts =
        FileExtensions.split(fileName).let { FileNameParts(it.baseName, it.extension, it.splitPart) }

    fun classify(fileName: String, mimeType: String, contentType: TelegramMediaContentType): MediaType {
        when (contentType) {
            TelegramMediaContentType.VIDEO -> return MediaType.VIDEO
            TelegramMediaContentType.AUDIO -> return MediaType.AUDIO
            TelegramMediaContentType.DOCUMENT -> Unit
        }
        val ext = splitName(fileName).extension
        val mime = mimeType.lowercase(Locale.ROOT)
        return when {
            ext in VIDEO_EXTENSIONS -> MediaType.VIDEO
            ext in SUBTITLE_EXTENSIONS || mime in SUBTITLE_MIMES -> MediaType.SUBTITLE
            ext in AUDIO_EXTENSIONS -> MediaType.AUDIO
            ext in ARCHIVE_EXTENSIONS -> MediaType.ARCHIVE
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            else -> MediaType.OTHER
        }
    }
}
