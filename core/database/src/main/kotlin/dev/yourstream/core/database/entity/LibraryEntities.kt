package dev.yourstream.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Library schema (v3).
 *
 *   library_item (MOVIE | SERIES | VIDEO)
 *     ├── season ── episode            (SERIES only)
 *     ├── media_link ── media          (files: versions of a movie/episode/video)
 *     ├── favorite, watch_state, watch_history, track_preference   ← USER DATA
 *     ├── subtitle_track, library_item_language, library_alias
 *
 * Items are derived from indexed files but stay stable: an item that carries user data is never
 * deleted automatically, and the same file re-appearing finds the same item via match_key, so
 * progress and favorites survive re-indexing, re-parsing and chat removal.
 */

/** A movie, a series, or a standalone video that could not be identified. */
@Entity(
    tableName = "library_item",
    indices = [
        Index(value = ["match_key"], unique = true),
        Index(value = ["kind", "hidden", "sort_title"]),
        // v9: the all-kinds title sort (default list) walks this instead of sorting the table per page.
        Index(value = ["sort_title", "id"]),
        Index(value = ["kind", "latest_added_at"]),
        Index(value = ["latest_added_at"]),
        Index(value = ["kind", "year"]),
        Index(value = ["last_watched_at"]),
        Index(value = ["normalized_title"]),
    ],
)
data class LibraryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MOVIE, SERIES or VIDEO. */
    val kind: String,
    /** "movie:<title key>:<year>", "series:<title key>", "video:<media id>". Stable identity. */
    @ColumnInfo(name = "match_key") val matchKey: String,
    val title: String,
    /** Lower-case title without a leading article, for alphabetical order. */
    @ColumnInfo(name = "sort_title") val sortTitle: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    val year: Int?,
    /** Text indexed by library_item_fts besides the title: keys, alternative titles, languages. */
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "title_confidence") val titleConfidence: Float,
    /** User edited or merged it: sync never renames or deletes it. */
    val locked: Boolean = false,
    /** User removed it from the library; files keep linking to it so it does not come back. */
    val hidden: Boolean = false,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // ---- cached aggregates (recomputed by sync; never the source of truth) ----
    /** Live, non-excluded files linked to the item (all versions, all episodes). */
    @ColumnInfo(name = "available_count") val availableCount: Int = 0,
    /** Newest indexed_at among live files: "recently added" (new episodes bump their series). */
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long? = null,
    @ColumnInfo(name = "max_resolution") val maxResolution: Int? = null,
    @ColumnInfo(name = "total_size") val totalSize: Long = 0,
    @ColumnInfo(name = "season_count") val seasonCount: Int = 0,
    @ColumnInfo(name = "episode_count") val episodeCount: Int = 0,
    /** Completed episodes (series) — kept by the watch repository. */
    @ColumnInfo(name = "watched_count") val watchedCount: Int = 0,
    /** Most recent watch activity on the item or any of its episodes. */
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long? = null,
)

@Fts4(contentEntity = LibraryItemEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "library_item_fts")
data class LibraryItemFtsEntity(
    val title: String,
    @ColumnInfo(name = "search_text") val searchText: String,
)

/** Old match keys pointing at the item they were merged into, so sync follows the merge. */
@Entity(
    tableName = "library_alias",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["item_id"])],
)
data class LibraryAliasEntity(
    @PrimaryKey @ColumnInfo(name = "match_key") val matchKey: String,
    @ColumnInfo(name = "item_id") val itemId: Long,
)

@Entity(
    tableName = "library_item_language",
    primaryKeys = ["item_id", "language"],
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["language", "item_id"])],
)
data class LibraryItemLanguageEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    /** ISO code ("ta", "hi"). */
    val language: String,
)

@Entity(
    tableName = "season",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["series_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["series_id", "number"], unique = true)],
)
data class SeasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    /** Season number; ABSOLUTE (-1) groups season-less numbering ("Shinchan 125"), 0 = specials. */
    val number: Int,
    val title: String?,
    /** Available episodes (cached). */
    @ColumnInfo(name = "episode_count") val episodeCount: Int = 0,
) {
    companion object {
        const val ABSOLUTE = -1
    }
}

@Entity(
    tableName = "episode",
    foreignKeys = [
        ForeignKey(LibraryItemEntity::class, ["id"], ["series_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SeasonEntity::class, ["id"], ["season_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["season_id", "number"], unique = true),
        Index(value = ["series_id", "season_number", "number"]),
        Index(value = ["series_id", "latest_added_at"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "season_id") val seasonId: Long,
    /** Copied from the season so episode lists sort on one index. */
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    val number: Int,
    /** Last episode of a multi-episode file ("E01-E03"). */
    @ColumnInfo(name = "number_end") val numberEnd: Int?,
    @ColumnInfo(name = "absolute_number") val absoluteNumber: Int?,
    val title: String?,
    @ColumnInfo(name = "air_date") val airDate: String?,
    @ColumnInfo(name = "available_count") val availableCount: Int = 0,
    @ColumnInfo(name = "latest_added_at") val latestAddedAt: Long? = null,
)

/** Which library entry a file belongs to. One row per file. */
@Entity(
    tableName = "media_link",
    foreignKeys = [
        ForeignKey(MediaEntity::class, ["id"], ["media_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(EpisodeEntity::class, ["id"], ["episode_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["item_id"]), Index(value = ["episode_id"])],
)
data class MediaLinkEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "episode_id") val episodeId: Long?,
    /** AUTO (sync may move it), MANUAL (user placed it), EXCLUDED (user removed the file). */
    val method: String,
    /** Fingerprint of the parse that produced this link; unchanged = nothing to do. */
    val signature: String,
    @ColumnInfo(name = "linked_at") val linkedAt: Long,
)

/** Files whose index row changed since the last library sync. Filled by SQLite triggers. */
@Entity(tableName = "library_pending")
data class LibraryPendingEntity(@PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long)

/** Items whose aggregates must be recomputed (e.g. links removed by cascades). Filled by triggers. */
@Entity(tableName = "library_dirty_item")
data class LibraryDirtyItemEntity(@PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long)

// ------------------------------------------------------------------ user data

@Entity(
    tableName = "favorite",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["added_at"])],
)
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Titles the user added to "My library" (the Library tab is only these). User data. */
@Entity(
    tableName = "user_library",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["added_at"])],
)
data class UserLibraryEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/**
 * Resume point and completion per playable unit: a movie/video (episode_key = 0) or one episode
 * (episode_key = episode id). episode_key is not a foreign key on purpose: an episode row may be
 * rebuilt, the progress must not vanish with it.
 */
@Entity(
    tableName = "watch_state",
    primaryKeys = ["item_id", "episode_key"],
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["completed", "last_watched_at"]), Index(value = ["last_watched_at"])],
)
data class WatchStateEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "episode_key") val episodeKey: Long,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val completed: Boolean,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
    /** The file (version) last played; not a foreign key (files come and go). */
    @ColumnInfo(name = "last_media_id") val lastMediaId: Long?,
)

/** Append-only log of viewing sessions (one row per sitting, updated while it lasts). */
@Entity(
    tableName = "watch_history",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["watched_at"]), Index(value = ["item_id", "episode_key", "watched_at"])],
)
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "episode_key") val episodeKey: Long,
    @ColumnInfo(name = "media_id") val mediaId: Long?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "watched_at") val watchedAt: Long,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val completed: Boolean,
)

/** Per-item audio/subtitle choices (series-wide for series). */
@Entity(
    tableName = "track_preference",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
)
data class TrackPreferenceEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "audio_language") val audioLanguage: String?,
    @ColumnInfo(name = "subtitle_language") val subtitleLanguage: String?,
    @ColumnInfo(name = "subtitles_enabled") val subtitlesEnabled: Boolean,
    @ColumnInfo(name = "subtitle_offset_ms") val subtitleOffsetMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** v6: the exact remembered audio track (language|label|channels|flags), found again in other files. */
    @ColumnInfo(name = "audio_signature") val audioSignature: String? = null,
    /** v6: the remembered subtitle track; null with subtitles_enabled = 0 means "off". */
    @ColumnInfo(name = "subtitle_signature") val subtitleSignature: String? = null,
    /** v6: MOVIE or SERIES (series rows apply to every episode). */
    val scope: String? = null,
)

/**
 * v6: the user's language settings (one row). Audio/subtitle preference lists are
 * comma-separated normalized codes in priority order ("ta,en,hi").
 */
@Entity(tableName = "language_settings")
data class LanguageSettingsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "audio_languages") val audioLanguages: String,
    @ColumnInfo(name = "subtitle_languages") val subtitleLanguages: String,
    @ColumnInfo(name = "fallback_language") val fallbackLanguage: String?,
    /** OFF, FOREIGN_ONLY or ALWAYS. */
    @ColumnInfo(name = "subtitle_mode") val subtitleMode: String,
    @ColumnInfo(name = "avoid_commentary") val avoidCommentary: Boolean,
    @ColumnInfo(name = "prefer_sdh") val preferSdh: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

// ------------------------------------------------------------------ tracks

/**
 * A subtitle available for an item/episode: a subtitle file found in Telegram (EXTERNAL), a
 * track inside a video file (EMBEDDED, discovered at playback), or one the user added (LOCAL).
 */
@Entity(
    tableName = "subtitle_track",
    foreignKeys = [
        ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(EpisodeEntity::class, ["id"], ["episode_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(MediaEntity::class, ["id"], ["media_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaEntity::class, ["id"], ["for_media_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["item_id", "episode_id"]),
        Index(value = ["episode_id"]),
        Index(value = ["media_id"], unique = true),
        Index(value = ["for_media_id", "track_index"]),
    ],
)
data class SubtitleTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "item_id") val itemId: Long?,
    @ColumnInfo(name = "episode_id") val episodeId: Long?,
    /** EXTERNAL: the subtitle file itself (index media row). */
    @ColumnInfo(name = "media_id") val mediaId: Long?,
    /** EMBEDDED: the video file containing it. */
    @ColumnInfo(name = "for_media_id") val forMediaId: Long?,
    @ColumnInfo(name = "track_index") val trackIndex: Int?,
    /** EXTERNAL, EMBEDDED or LOCAL. */
    val source: String,
    val language: String?,
    val label: String?,
    val format: String?,
    val forced: Boolean,
    val sdh: Boolean,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Audio tracks of a video file: guessed from the name (PARSED) or read from the file (PROBED). */
@Entity(
    tableName = "audio_track",
    foreignKeys = [ForeignKey(MediaEntity::class, ["id"], ["media_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["media_id", "source", "track_index"], unique = true), Index(value = ["language"])],
)
data class AudioTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "track_index") val trackIndex: Int,
    /** PARSED or PROBED. */
    val source: String,
    val language: String?,
    val codec: String?,
    val channels: String?,
    val label: String?,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
)

/**
 * "Remove from Continue Watching" (v8). User data: the item stays out of Continue Watching and
 * counts as abandoned until it is played again after [dismissedAt].
 */
@Entity(
    tableName = "watch_dismissal",
    foreignKeys = [ForeignKey(LibraryItemEntity::class, ["id"], ["item_id"], onDelete = ForeignKey.CASCADE)],
)
data class WatchDismissalEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "dismissed_at") val dismissedAt: Long,
)
