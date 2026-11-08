package dev.yourstream.media.filename

/**
 * One extracted value, with how sure the parser is (0..1) and the text it came from.
 * [source] says whether it was read from the file name, the caption or the chat title.
 */
public data class Field<out T>(
    val value: T,
    val confidence: Float,
    val raw: String,
    val source: FieldSource = FieldSource.FILE_NAME,
)

public enum class FieldSource { FILE_NAME, CAPTION, CHAT_TITLE }

/** A spoken or subtitle language. [code] is ISO 639-1 when one exists, else ISO 639-2/3. */
public data class Language(val code: String, val name: String)

public data class AudioInfo(
    /** Normalised codec names in order of appearance: "DD+", "AAC", "DTS-HD MA", … */
    val codecs: List<String>,
    /** "2.0", "5.1", "7.1". */
    val channels: String?,
    val bitrateKbps: Int?,
    val isDualAudio: Boolean,
    val isMultiAudio: Boolean,
    val confidence: Float,
)

public data class SubtitleInfo(
    val languages: List<Language>,
    /** Burnt into the picture (HC / hardsub). Null when the name does not say. */
    val hardcoded: Boolean?,
    val forced: Boolean,
    /** SDH / CC: subtitles for the deaf and hard of hearing. */
    val sdh: Boolean,
    /** The file itself is a subtitle file (.srt, .ass …) and this describes its content. */
    val isSubtitleFile: Boolean,
    val confidence: Float,
)

public enum class MediaKindHint {
    MOVIE,
    EPISODE,
    /** A whole season (or several) in one file/archive name. */
    SEASON_PACK,
    UNKNOWN,
}

public enum class ReleaseFlag { SAMPLE, TRAILER, THREE_D, COMPLETE, PROPER, REPACK }

/**
 * Everything [MediaFilenameParser] understood about a file name.
 *
 * [original] is the input exactly as given and is never altered. Every field is optional
 * because real-world names are inconsistent; check confidences before trusting a value.
 */
public data class FilenameMetadata(
    val original: String,
    /** The name with extension, noise and separators removed (for display/debugging). */
    val cleanedName: String,
    val extension: String?,
    /** Display title: separators removed, casing tidied ("Movie Name"). */
    val title: Field<String>?,
    /** Search/matching key: lower-case, accents folded, punctuation dropped ("movie name"). */
    val normalizedTitle: String?,
    val alternativeTitle: Field<String>?,
    val year: Field<Int>?,
    val season: Field<Int>?,
    /** Last season of a multi-season pack ("S01-S03"). */
    val seasonEnd: Int?,
    val episode: Field<Int>?,
    /** Last episode of a range ("E01-E03" → 3). */
    val episodeEnd: Int?,
    /** Episode number without a season, as anime and long-running kids' shows use ("Shinchan 125"). */
    val absoluteEpisode: Field<Int>?,
    /** "2024-03-15" for daily shows. */
    val airDate: Field<String>?,
    /** Vertical lines: 480, 720, 1080, 2160 … */
    val resolution: Field<Int>?,
    /** Release source: WEB-DL, WEBRip, BluRay, HDRip, HDTV, CAM, PreDVD … */
    val quality: Field<String>?,
    /** Extra quality words: HQ, PROPER, REPACK, UNTOUCHED … */
    val qualityTags: List<String>,
    /** Streaming origin: Netflix, Amazon, Hotstar, ZEE5 … */
    val streamingService: Field<String>?,
    val videoCodec: Field<String>?,
    val bitDepth: Int?,
    /** "HDR10+", "HDR10", "HDR", "DV", "HLG" (several joined by space, e.g. "DV HDR10"). */
    val hdr: Field<String>?,
    val audio: AudioInfo?,
    /** Spoken languages, each with its own confidence. */
    val languages: List<Field<Language>>,
    val isDubbed: Boolean,
    val subtitles: SubtitleInfo?,
    val edition: Field<String>?,
    val releaseGroup: Field<String>?,
    /** Part of a split or multi-disc release (".001" → 1, "CD2" → 2). */
    val partNumber: Field<Int>?,
    val flags: Set<ReleaseFlag>,
    val kind: Field<MediaKindHint>,
    /** 0..1 summary of how well the name was understood. */
    val confidence: Float,
    /** Human-readable notes about guesses and fallbacks. */
    val warnings: List<String>,
    val parserVersion: Int,
) {
    /** All per-field confidences, keyed by field name, for storage and debugging. */
    public fun fieldConfidences(): Map<String, Float> = buildMap {
        title?.let { put("title", it.confidence) }
        alternativeTitle?.let { put("alternativeTitle", it.confidence) }
        year?.let { put("year", it.confidence) }
        season?.let { put("season", it.confidence) }
        episode?.let { put("episode", it.confidence) }
        absoluteEpisode?.let { put("absoluteEpisode", it.confidence) }
        airDate?.let { put("airDate", it.confidence) }
        resolution?.let { put("resolution", it.confidence) }
        quality?.let { put("quality", it.confidence) }
        streamingService?.let { put("streamingService", it.confidence) }
        videoCodec?.let { put("videoCodec", it.confidence) }
        hdr?.let { put("hdr", it.confidence) }
        audio?.let { put("audio", it.confidence) }
        if (languages.isNotEmpty()) put("language", languages.maxOf { it.confidence })
        subtitles?.let { put("subtitles", it.confidence) }
        edition?.let { put("edition", it.confidence) }
        releaseGroup?.let { put("releaseGroup", it.confidence) }
        partNumber?.let { put("part", it.confidence) }
        put("kind", kind.confidence)
    }
}

/** Optional context that helps when the file name itself says little. */
public data class ParseHints(
    /** Telegram message caption; its first meaningful line is used when the name is generic. */
    val caption: String? = null,
    /** Chat/channel title; last-resort title source ("Doraemon Tamil Episodes"). */
    val chatTitle: String? = null,
)
