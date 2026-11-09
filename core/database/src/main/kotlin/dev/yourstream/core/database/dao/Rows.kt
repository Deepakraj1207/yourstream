package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import dev.yourstream.core.database.entity.IndexedChatEntity
import dev.yourstream.core.database.entity.MediaEntity
import dev.yourstream.core.database.entity.ParsedMediaEntity

/** Chat plus counts computed in SQL (never by loading rows). */
data class ChatStatusRow(
    @Embedded val chat: IndexedChatEntity,
    @ColumnInfo(name = "media_count") val mediaCount: Int,
    @ColumnInfo(name = "deleted_count") val deletedCount: Int,
    /** 1 when every scan cursor of the chat finished its backfill (0 if no cursor yet). */
    @ColumnInfo(name = "backfill_complete") val backfillComplete: Boolean,
)

/** Narrow projection for lists: only the columns a result row shows. */
data class MediaSummaryRow(
    val id: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int?,
    @ColumnInfo(name = "message_date") val messageDate: Long,
    @ColumnInfo(name = "parsed_title") val parsedTitle: String?,
    val season: Int?,
    val episode: Int?,
    @ColumnInfo(name = "absolute_episode") val absoluteEpisode: Int?,
    val resolution: Int?,
    @ColumnInfo(name = "is_duplicate") val isDuplicate: Boolean,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
)

data class MediaWithParsed(
    @Embedded val media: MediaEntity,
    @Relation(parentColumn = "id", entityColumn = "media_id") val parsed: ParsedMediaEntity?,
)

/** Minimal identity used by the writer to decide insert vs update. */
data class MediaKeyRow(
    val id: Long,
    @ColumnInfo(name = "file_unique_id") val fileUniqueId: String,
    @ColumnInfo(name = "canonical_media_id") val canonicalMediaId: Long?,
    @ColumnInfo(name = "duplicate_kind") val duplicateKind: String?,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)

data class DuplicateKeyRow(
    val id: Long,
    @ColumnInfo(name = "file_unique_id") val fileUniqueId: String,
    val fingerprint: String?,
)

/** Input for re-parsing: the stored original name and the context hints. */
data class ReparseRow(
    val id: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    val caption: String,
    @ColumnInfo(name = "audio_title") val audioTitle: String?,
    @ColumnInfo(name = "audio_performer") val audioPerformer: String?,
    @ColumnInfo(name = "chat_title") val chatTitle: String?,
    @ColumnInfo(name = "context_caption") val contextCaption: String? = null,
)

/** A file whose title is missing or only guessed, for looking up the post above it. */
data class UntitledMediaRow(
    val id: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "message_date") val messageDate: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    val caption: String,
    @ColumnInfo(name = "audio_title") val audioTitle: String?,
    @ColumnInfo(name = "audio_performer") val audioPerformer: String?,
    @ColumnInfo(name = "chat_title") val chatTitle: String?,
)

data class ContextCaptionRow(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "context_caption") val contextCaption: String,
)
