package dev.yourstream.media.api.model

/** What a file is, decided from MIME type and extension (not from how Telegram sent it). */
enum class MediaType { VIDEO, AUDIO, SUBTITLE, ARCHIVE, OTHER }

/** How much of the file TDLib has on disk, as of the last time the index saw it. */
enum class DownloadState { NOT_DOWNLOADED, PARTIAL, COMPLETE }

/**
 * Why a record points at another one ([IndexedMedia.canonicalMediaId]).
 * EXACT: the same Telegram upload (same remote unique id), e.g. a forward.
 * PROBABLE: a different upload with the same normalised filename and byte size.
 */
enum class DuplicateKind { EXACT, PROBABLE }

/** How Telegram delivered it. */
enum class SourceContentType { VIDEO, DOCUMENT, AUDIO }

/**
 * Structured metadata derived from the original file name (which is stored unchanged next to
 * it). Produced by :media:filename; every field is optional and [fieldConfidence] says how sure
 * the parser was about each one (0..1).
 */
data class ParsedMedia(
    val title: String?,
    /** Lower-case, accent-folded matching key ("tom and jerry"). */
    val normalizedTitle: String? = null,
    val alternativeTitle: String? = null,
    val year: Int?,
    val season: Int?,
    val seasonEnd: Int? = null,
    val episode: Int?,
    /** Last episode of a range such as "E01-E03". */
    val episodeEnd: Int?,
    /** Season-less episode number ("Shinchan 125", anime "- 12"). */
    val absoluteEpisode: Int? = null,
    /** "2024-03-15" for date-numbered shows. */
    val airDate: String? = null,
    /** Vertical resolution in lines (480, 720, 1080, 2160). */
    val resolution: Int?,
    /** Release source / quality: WEB-DL, BluRay, HDRip, CAM … */
    val quality: String?,
    val qualityTags: List<String> = emptyList(),
    val streamingService: String? = null,
    val videoCodec: String?,
    val bitDepth: Int? = null,
    val hdr: String?,
    val audioCodecs: List<String>,
    val audioChannels: String? = null,
    val audioBitrateKbps: Int? = null,
    val isDualAudio: Boolean = false,
    val isMultiAudio: Boolean = false,
    /** Spoken languages as ISO codes ("ta", "hi", "en"). */
    val languages: List<String>,
    val isDubbed: Boolean = false,
    /** Subtitle languages as ISO codes ("en"; "mul" = several). */
    val subtitleLanguages: List<String>,
    /** True = burnt in, false = soft, null = unknown. */
    val subtitlesHardcoded: Boolean? = null,
    val edition: String?,
    val releaseGroup: String?,
    /** For split files: 1 for ".001"/"CD1". */
    val partNumber: Int?,
    /** SAMPLE, TRAILER, THREE_D, COMPLETE, PROPER, REPACK. */
    val flags: Set<String> = emptySet(),
    /** MOVIE, EPISODE, SEASON_PACK or UNKNOWN. */
    val kind: String = "UNKNOWN",
    /** Field name → confidence (title, year, season, episode, language, resolution …). */
    val fieldConfidence: Map<String, Float> = emptyMap(),
    /** 0..1: how much of the name the parser understood. */
    val confidence: Float,
    val warnings: List<String> = emptyList(),
    val parserVersion: Int,
) {
    val isEpisode: Boolean get() = episode != null || absoluteEpisode != null || airDate != null
}

/** A full index record. */
data class IndexedMedia(
    val id: Long,
    val chatId: Long,
    val chatTitle: String?,
    val messageId: Long,
    val albumId: Long,
    /** Epoch seconds. */
    val messageDate: Long,
    val editDate: Long,
    /** Session-local TDLib file id. Valid only until TDLib restarts; re-resolve via chat/message. */
    val fileId: Int,
    val fileUniqueId: String,
    val remoteFileId: String,
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
    val canonicalMediaId: Long?,
    val duplicateKind: DuplicateKind?,
    val isDeleted: Boolean,
    /** Epoch millis. */
    val deletedAt: Long?,
    val indexedAt: Long,
    val updatedAt: Long,
    val parsed: ParsedMedia?,
)

/** A light row for lists and search results. */
data class MediaSummary(
    val id: Long,
    val chatId: Long,
    val messageId: Long,
    val fileName: String,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val durationSeconds: Int?,
    val messageDate: Long,
    val parsedTitle: String?,
    val season: Int?,
    val episode: Int?,
    val absoluteEpisode: Int?,
    val resolution: Int?,
    val isDuplicate: Boolean,
    val isDeleted: Boolean,
)

/** What to search for. Empty [text] lists everything matching the filters, newest first. */
data class MediaQuery(
    val text: String = "",
    val chatId: Long? = null,
    val types: Set<MediaType> = emptySet(),
    val includeDeleted: Boolean = false,
    /** False hides records that duplicate another one. */
    val includeDuplicates: Boolean = true,
)
