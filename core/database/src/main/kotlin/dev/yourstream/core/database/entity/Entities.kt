package dev.yourstream.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chat the user chose to index. User data: survives re-indexing.
 * Deleting a row cascades to its cursors, media and runs.
 */
@Entity(tableName = "indexed_chat")
data class IndexedChatEntity(
    @PrimaryKey @ColumnInfo(name = "chat_id") val chatId: Long,
    val title: String,
    /** PRIVATE / GROUP / CHANNEL. */
    val category: String,
    val enabled: Boolean,
    /** IndexState name. */
    val state: String,
    @ColumnInfo(name = "last_error") val lastError: String?,
    /** Epoch millis of the last run that completed its work. */
    @ColumnInfo(name = "last_indexed_at") val lastIndexedAt: Long?,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long?,
    /** Deletion check resumes after this message id (keyset cursor); 0 = from the start. */
    @ColumnInfo(name = "verify_cursor") val verifyCursor: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Paused by the user: no indexing runs until resumed (live updates still apply). */
    @ColumnInfo(name = "paused", defaultValue = "0") val paused: Boolean = false,
)

/**
 * Where scanning stopped, per chat and Telegram search filter (VIDEO / DOCUMENT / AUDIO).
 * This is what makes indexing incremental and resumable.
 */
@Entity(
    tableName = "scan_cursor",
    primaryKeys = ["chat_id", "filter"],
    foreignKeys = [ForeignKey(IndexedChatEntity::class, ["chat_id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
)
data class ScanCursorEntity(
    @ColumnInfo(name = "chat_id") val chatId: Long,
    val filter: String,
    /** High-water mark: newest message id the head pass has fully covered. 0 = never scanned. */
    @ColumnInfo(name = "newest_message_id") val newestMessageId: Long,
    /** Backfill continues from (older than) this id. 0 with !backfillComplete = not started. */
    @ColumnInfo(name = "backfill_from_message_id") val backfillFromMessageId: Long,
    @ColumnInfo(name = "backfill_complete") val backfillComplete: Boolean,
    /** Telegram's estimate of matching messages, -1 unknown. */
    @ColumnInfo(name = "estimated_total") val estimatedTotal: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One file in one Telegram message. Rebuildable cache (except nothing user-made lives here). */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["chat_id", "message_id"], unique = true),
        Index(value = ["chat_id", "is_deleted"]),
        Index(value = ["chat_id", "message_date"]),
        Index(value = ["message_date"]),
        Index(value = ["file_unique_id"]),
        Index(value = ["fingerprint"]),
        Index(value = ["canonical_media_id"]),
        Index(value = ["file_id"]),
        Index(value = ["media_type"]),
        // Files of one album share the caption Telegram stores on only one of them.
        Index(value = ["chat_id", "album_id"]),
    ],
    foreignKeys = [ForeignKey(IndexedChatEntity::class, ["chat_id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "message_date") val messageDate: Long,
    @ColumnInfo(name = "edit_date") val editDate: Long,
    /** Session-local TDLib id: refreshed on every sighting, never used as a key. */
    @ColumnInfo(name = "file_id") val fileId: Int,
    @ColumnInfo(name = "file_unique_id") val fileUniqueId: String,
    @ColumnInfo(name = "remote_file_id") val remoteFileId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val extension: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int?,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "video_codec") val videoCodec: String?,
    /** Comma-separated. */
    @ColumnInfo(name = "audio_codecs") val audioCodecs: String,
    @ColumnInfo(name = "supports_streaming") val supportsStreaming: Boolean,
    /** MediaType name. */
    @ColumnInfo(name = "media_type") val mediaType: String,
    /** SourceContentType name. */
    @ColumnInfo(name = "content_type") val contentType: String,
    val caption: String,
    @ColumnInfo(name = "audio_title") val audioTitle: String?,
    @ColumnInfo(name = "audio_performer") val audioPerformer: String?,
    /** DownloadState name. */
    @ColumnInfo(name = "download_state") val downloadState: String,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    @ColumnInfo(name = "local_path") val localPath: String?,
    /** Normalised parsed title (+ audio tags), indexed by FTS next to file_name and caption. */
    @ColumnInfo(name = "search_title") val searchTitle: String,
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    /** Set when this row duplicates another; points at the group's canonical (oldest) row. */
    @ColumnInfo(name = "canonical_media_id") val canonicalMediaId: Long?,
    /** DuplicateKind name. */
    @ColumnInfo(name = "duplicate_kind") val duplicateKind: String?,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Last time a scan or check saw the message alive. */
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    /**
     * v12: the text of the post above the file (a poster's caption, a text post), read when the
     * file's own name and caption carry no title. Null: not looked for yet; "": looked, none.
     */
    @ColumnInfo(name = "context_caption") val contextCaption: String? = null,
)

/**
 * Metadata parsed from the original file name (media.file_name, never modified). Kept apart so
 * it can be recomputed from the stored name whenever the parser improves (parser_version).
 * Added in v2: everything from normalized_title down. Lists are comma-joined; field_confidence
 * is "field=0.95;field=0.80".
 */
@Entity(
    tableName = "media_parsed",
    foreignKeys = [ForeignKey(MediaEntity::class, ["id"], ["media_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["parser_version"]), Index(value = ["normalized_title"])],
)
data class ParsedMediaEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    val title: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    @ColumnInfo(name = "episode_end") val episodeEnd: Int?,
    val resolution: Int?,
    /** Release source / quality (WEB-DL, BluRay …). Column name kept from v1. */
    @ColumnInfo(name = "source") val quality: String?,
    @ColumnInfo(name = "video_codec") val videoCodec: String?,
    @ColumnInfo(name = "audio_codecs") val audioCodecs: String,
    val hdr: String?,
    val languages: String,
    @ColumnInfo(name = "subtitle_languages") val subtitleLanguages: String,
    val edition: String?,
    @ColumnInfo(name = "release_group") val releaseGroup: String?,
    @ColumnInfo(name = "part_number") val partNumber: Int?,
    val confidence: Float,
    @ColumnInfo(name = "parser_version") val parserVersion: Int,
    // ---- v2 ----
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String? = null,
    @ColumnInfo(name = "alternative_title") val alternativeTitle: String? = null,
    @ColumnInfo(name = "season_end") val seasonEnd: Int? = null,
    @ColumnInfo(name = "absolute_episode") val absoluteEpisode: Int? = null,
    @ColumnInfo(name = "air_date") val airDate: String? = null,
    @ColumnInfo(name = "quality_tags", defaultValue = "") val qualityTags: String = "",
    @ColumnInfo(name = "streaming_service") val streamingService: String? = null,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int? = null,
    @ColumnInfo(name = "audio_channels") val audioChannels: String? = null,
    @ColumnInfo(name = "audio_bitrate_kbps") val audioBitrateKbps: Int? = null,
    @ColumnInfo(name = "dual_audio", defaultValue = "0") val dualAudio: Boolean = false,
    @ColumnInfo(name = "multi_audio", defaultValue = "0") val multiAudio: Boolean = false,
    @ColumnInfo(name = "dubbed", defaultValue = "0") val dubbed: Boolean = false,
    @ColumnInfo(name = "subtitles_hardcoded") val subtitlesHardcoded: Boolean? = null,
    @ColumnInfo(name = "flags", defaultValue = "") val flags: String = "",
    @ColumnInfo(name = "kind", defaultValue = "UNKNOWN") val kind: String = "UNKNOWN",
    @ColumnInfo(name = "field_confidence", defaultValue = "") val fieldConfidence: String = "",
    @ColumnInfo(name = "warnings", defaultValue = "") val warnings: String = "",
)

/**
 * External-content FTS4 index over [MediaEntity]. Room generates the triggers that keep it in
 * sync; rowid = media.id. unicode61 folds case for non-Latin scripts too.
 */
@Fts4(contentEntity = MediaEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "media_fts")
data class MediaFtsEntity(
    @ColumnInfo(name = "file_name") val fileName: String,
    val caption: String,
    @ColumnInfo(name = "search_title") val searchTitle: String,
)

/** History of indexing runs: status, counts and errors per run. */
@Entity(
    tableName = "index_run",
    indices = [Index(value = ["chat_id", "started_at"])],
    foreignKeys = [ForeignKey(IndexedChatEntity::class, ["chat_id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
)
data class IndexRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    val mode: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    /** IndexRunResult.Outcome name; null while running. */
    val outcome: String?,
    val pages: Int,
    @ColumnInfo(name = "messages_seen") val messagesSeen: Int,
    @ColumnInfo(name = "media_added") val mediaAdded: Int,
    @ColumnInfo(name = "media_updated") val mediaUpdated: Int,
    @ColumnInfo(name = "media_deleted") val mediaDeleted: Int,
    val error: String?,
)
