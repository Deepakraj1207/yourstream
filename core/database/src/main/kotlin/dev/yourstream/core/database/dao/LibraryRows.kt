package dev.yourstream.core.database.dao

import androidx.room.ColumnInfo

/** What the library sync needs to know about one indexed file. */
data class LinkInputRow(
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    @ColumnInfo(name = "canonical_media_id") val canonicalMediaId: Long?,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
    val title: String?,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String?,
    @ColumnInfo(name = "alternative_title") val alternativeTitle: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    @ColumnInfo(name = "episode_end") val episodeEnd: Int?,
    @ColumnInfo(name = "absolute_episode") val absoluteEpisode: Int?,
    @ColumnInfo(name = "air_date") val airDate: String?,
    val kind: String?,
    val confidence: Float?,
    @ColumnInfo(name = "field_confidence") val fieldConfidence: String?,
    val languages: String?,
    @ColumnInfo(name = "subtitle_languages") val subtitleLanguages: String?,
    @ColumnInfo(name = "audio_codecs") val audioCodecs: String?,
    @ColumnInfo(name = "audio_channels") val audioChannels: String?,
    val extension: String?,
    @ColumnInfo(name = "parser_version") val parserVersion: Int?,
    @ColumnInfo(name = "link_item_id") val linkItemId: Long?,
    @ColumnInfo(name = "link_episode_id") val linkEpisodeId: Long?,
    @ColumnInfo(name = "link_method") val linkMethod: String?,
    @ColumnInfo(name = "link_signature") val linkSignature: String?,
)

/** One row of a library list (movies, series, search results, recently added …). */
data class LibraryItemRow(
    val id: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "season_count") val seasonCount: Int,
    @ColumnInfo(name = "episode_count") val episodeCount: Int,
    @ColumnInfo(name = "watched_count") val watchedCount: Int,
    @ColumnInfo(name = "max_resolution") val maxResolution: Int?,
    @ColumnInfo(name = "total_size") val totalSize: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long?,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val completed: Boolean,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "metadata_title") val metadataTitle: String?,
)

data class EpisodeRow(
    val id: Long,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "season_id") val seasonId: Long,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    val number: Int,
    @ColumnInfo(name = "number_end") val numberEnd: Int?,
    @ColumnInfo(name = "absolute_number") val absoluteNumber: Int?,
    val title: String?,
    @ColumnInfo(name = "air_date") val airDate: String?,
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val completed: Boolean,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long?,
)

/** A file that plays an item/episode ("version"), best first. */
data class MediaVersionRow(
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int?,
    val resolution: Int?,
    val quality: String?,
    @ColumnInfo(name = "video_codec") val videoCodec: String?,
    val languages: String?,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    @ColumnInfo(name = "is_duplicate") val isDuplicate: Boolean,
    @ColumnInfo(name = "episode_id") val episodeId: Long?,
    val method: String,
    @ColumnInfo(name = "download_state") val downloadState: String,
)

/** Continue-watching / recently-watched entry. */
data class WatchEntryRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "episode_key") val episodeKey: Long,
    val kind: String,
    val title: String,
    val year: Int?,
    @ColumnInfo(name = "season_number") val seasonNumber: Int?,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int?,
    @ColumnInfo(name = "episode_title") val episodeTitle: String?,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val completed: Boolean,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    @ColumnInfo(name = "last_media_id") val lastMediaId: Long?,
)

data class SyncCountsRow(
    @ColumnInfo(name = "available_count") val availableCount: Int,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long?,
)
