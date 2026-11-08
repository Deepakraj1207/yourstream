package dev.yourstream.media.parser

import dev.yourstream.media.api.model.MediaType
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import java.util.Locale

/** Decides what a file is. Telegram's MIME type for documents is often just octet-stream. */
object MediaClassifier {

    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "wmv", "flv", "3gp", "mpg", "mpeg", "vob", "ogv", "rmvb", "divx",
    )
    val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "mka", "wma", "ac3", "eac3", "dts", "alac", "aiff")
    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "smi", "ttml", "dfxp")
    val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    private val SPLIT_PART = Regex("\\.(\\d{3})$")
    private val RAR_PART = Regex("\\.part(\\d{1,3})\\.rar$", RegexOption.IGNORE_CASE)

    private val SUBTITLE_MIMES = setOf("application/x-subrip", "text/vtt", "text/x-ssa", "text/x-ass", "application/x-ass")

    data class FileNameParts(
        /** Name without media extension and split suffix. */
        val baseName: String,
        /** Real media extension, lower-case ("mkv" for "film.mkv.001"). */
        val extension: String?,
        /** 1 for ".001", 2 for ".002"… null if not a split part. */
        val splitPart: Int?,
    )

    fun splitName(fileName: String): FileNameParts {
        var name = fileName.trim()
        var part: Int? = null
        SPLIT_PART.find(name)?.let {
            part = it.groupValues[1].toInt()
            name = name.removeRange(it.range)
        }
        RAR_PART.find(name)?.let {
            part = it.groupValues[1].toInt()
            name = name.removeRange(it.range) + ".rar"
        }
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && dot >= name.length - 6) name.substring(dot + 1).lowercase(Locale.ROOT) else null
        val known = ext != null && (ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS || ext in SUBTITLE_EXTENSIONS || ext in ARCHIVE_EXTENSIONS)
        return FileNameParts(
            baseName = if (known) name.substring(0, dot) else name,
            extension = if (known) ext else ext?.takeIf { it.all(Char::isLetterOrDigit) },
            splitPart = part,
        )
    }

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
