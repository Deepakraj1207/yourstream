package dev.yourstream.library.api

/** What a library entry is. VIDEO = a standalone file whose metadata could not be identified. */
enum class LibraryKind { MOVIE, SERIES, VIDEO }

enum class LibrarySort {
    /** Alphabetical, ignoring a leading "The/A/An". */
    TITLE,
    /** Newest file added first (a new episode bumps its series). */
    RECENTLY_ADDED,
    /** Most recently watched first; never-watched last. */
    RECENTLY_WATCHED,
    YEAR,
    SIZE,
}

enum class WatchFilter { ANY, UNWATCHED, IN_PROGRESS, WATCHED }

/** Filters for any library list. Defaults: everything available, A→Z. */
data class LibraryQuery(
    val text: String = "",
    /** Empty = all kinds. */
    val kinds: Set<LibraryKind> = emptySet(),
    val favoritesOnly: Boolean = false,
    /** Only titles the user added to "My library" (the Library tab). */
    val myLibraryOnly: Boolean = false,
    val watch: WatchFilter = WatchFilter.ANY,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** ISO language code a file of the item is in ("ta"). */
    val language: String? = null,
    val minResolution: Int? = null,
    /** Include items whose files are all gone (kept because of progress/favorites). */
    val includeUnavailable: Boolean = false,
    val includeHidden: Boolean = false,
    val sort: LibrarySort = LibrarySort.TITLE,
    /** Reverses the natural direction of [sort] (TITLE A→Z, others newest/biggest first). */
    val reversed: Boolean = false,
)

data class WatchProgress(
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val lastWatchedAt: Long?,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** One list row. */
data class LibraryItemSummary(
    val id: Long,
    val kind: LibraryKind,
    val title: String,
    val year: Int?,
    /** Playable files (all versions/episodes). 0 = unavailable (kept for its user data). */
    val availableCount: Int,
    val seasonCount: Int,
    val episodeCount: Int,
    val watchedEpisodeCount: Int,
    val maxResolution: Int?,
    val totalSize: Long,
    val addedAt: Long,
    val latestAddedAt: Long?,
    val lastWatchedAt: Long?,
    val isFavorite: Boolean,
    /** Movie/video progress (series: null; see watchedEpisodeCount). */
    val progress: WatchProgress?,
    /** From matched external metadata (Phase 5), when available. */
    val posterUrl: String? = null,
    /** Official title from external metadata, when matched (title stays the library's own). */
    val metadataTitle: String? = null,
    /** The user added it to "My library". */
    val inLibrary: Boolean = false,
    /** "chatId:messageId" of a file of this title, for its Telegram thumbnail (when there is no poster). */
    val thumbnailRef: String? = null,
) {
    val isAvailable: Boolean get() = availableCount > 0
    val unwatchedEpisodeCount: Int get() = (episodeCount - watchedEpisodeCount).coerceAtLeast(0)
}

/** What to play / track progress for: a movie or video ([episodeId] null) or one episode. */
data class PlaybackTarget(val itemId: Long, val episodeId: Long? = null) {
    /** watch_state key: 0 for the item itself. */
    val episodeKey: Long get() = episodeId ?: 0L
}

data class Season(
    val id: Long,
    val seriesId: Long,
    /** -1 = season-less numbering ("Episodes"), 0 = specials. */
    val number: Int,
    val title: String?,
    val episodeCount: Int,
) {
    val displayName: String
        get() = title ?: when (number) {
            ABSOLUTE -> "Episodes"
            0 -> "Specials"
            else -> "Season $number"
        }

    companion object {
        const val ABSOLUTE = -1
    }
}

data class Episode(
    val id: Long,
    val seriesId: Long,
    val seasonId: Long,
    val seasonNumber: Int,
    val number: Int,
    val numberEnd: Int?,
    val absoluteNumber: Int?,
    val title: String?,
    val airDate: String?,
    val availableCount: Int,
    val latestAddedAt: Long?,
    val progress: WatchProgress?,
) {
    val target: PlaybackTarget get() = PlaybackTarget(seriesId, id)
    val label: String
        get() = buildString {
            if (seasonNumber >= 0) append("S%02d".format(seasonNumber))
            append("E%02d".format(number))
            numberEnd?.let { append("-E%02d".format(it)) }
        }
}

/** A file that plays an item or episode. Several = versions (720p/1080p, dubs, forwards). */
data class MediaVersion(
    val mediaId: Long,
    val chatId: Long,
    val messageId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val durationSeconds: Int?,
    val resolution: Int?,
    val quality: String?,
    val videoCodec: String?,
    val languages: List<String>,
    val isDeleted: Boolean,
    val isDuplicate: Boolean,
    val episodeId: Long?,
    val isManual: Boolean,
    val isExcluded: Boolean,
    val downloadState: String,
)

data class LibraryItemDetails(
    val summary: LibraryItemSummary,
    val normalizedTitle: String,
    val locked: Boolean,
    val hidden: Boolean,
    val languages: List<String>,
    /** Files of the item itself (movie/video) or, for a series, files not tied to an episode. */
    val versions: List<MediaVersion>,
    val seasons: List<Season>,
)

data class ContinueWatchingEntry(
    val target: PlaybackTarget,
    val kind: LibraryKind,
    val title: String,
    val year: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val progress: WatchProgress,
    val lastMediaId: Long?,
)

data class WatchHistoryEntry(
    val id: Long,
    val target: PlaybackTarget,
    val mediaId: Long?,
    val startedAt: Long,
    val watchedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
)

enum class SubtitleSource { EXTERNAL, EMBEDDED, LOCAL }

data class SubtitleTrack(
    val id: Long,
    val itemId: Long?,
    val episodeId: Long?,
    val source: SubtitleSource,
    /** EXTERNAL: the subtitle file in the index. */
    val mediaId: Long?,
    /** EMBEDDED: the video file that contains it, and its track index. */
    val forMediaId: Long?,
    val trackIndex: Int?,
    val language: String?,
    val label: String?,
    val format: String?,
    val forced: Boolean,
    val sdh: Boolean,
    val localPath: String?,
)

enum class AudioTrackSource { PARSED, PROBED }

data class AudioTrack(
    val id: Long,
    val mediaId: Long,
    val trackIndex: Int,
    /** PARSED = guessed from the file name; PROBED = read from the file (authoritative). */
    val source: AudioTrackSource,
    val language: String?,
    val codec: String?,
    val channels: String?,
    val label: String?,
    val isDefault: Boolean,
)

data class TrackPreference(
    val itemId: Long,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitlesEnabled: Boolean,
    val subtitleOffsetMs: Long,
)

data class LibrarySyncResult(
    val filesProcessed: Int,
    val linksChanged: Int,
    val itemsCreated: Int,
    val itemsRemoved: Int,
    val subtitlesAttached: Int,
)
