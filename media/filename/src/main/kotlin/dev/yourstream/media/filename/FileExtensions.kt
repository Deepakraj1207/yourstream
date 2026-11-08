package dev.yourstream.media.filename

import java.util.Locale

/** Known media extensions and split-file suffixes. */
public object FileExtensions {
    public val VIDEO: Set<String> = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "wmv", "flv", "3gp", "mpg", "mpeg", "vob", "ogv", "rmvb", "divx", "f4v",
    )
    public val AUDIO: Set<String> = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "mka", "wma", "ac3", "eac3", "dts", "alac", "aiff", "m4b")
    public val SUBTITLE: Set<String> = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "smi", "ttml", "dfxp")
    public val ARCHIVE: Set<String> = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    public val ALL: Set<String> = VIDEO + AUDIO + SUBTITLE + ARCHIVE

    private val SPLIT_PART = Regex("\\.(\\d{3})$")
    private val RAR_PART = Regex("\\.part(\\d{1,3})\\.rar$", RegexOption.IGNORE_CASE)

    public data class Parts(
        /** Name without the media extension and split suffix (the original text, not cleaned). */
        val baseName: String,
        /** Media extension in lower case ("mkv" for "film.mkv.001"), or null when not recognised. */
        val extension: String?,
        /** 1 for ".001"/".part1.rar", null if not a split part. */
        val splitPart: Int?,
    )

    public fun split(fileName: String): Parts {
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
        if (dot <= 0 || dot < name.length - 6) return Parts(name, null, part)
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return if (ext in ALL) Parts(name.substring(0, dot), ext, part) else Parts(name, null, part)
    }
}
