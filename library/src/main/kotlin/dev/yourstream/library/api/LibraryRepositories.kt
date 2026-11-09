package dev.yourstream.library.api

import androidx.paging.PagingData
import dev.yourstream.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Library-wide operations on items of any kind. */
interface MediaRepository {
    /** Search + filter + sort, paged from SQLite. */
    fun search(query: LibraryQuery, pageSize: Int = 50): Flow<PagingData<LibraryItemSummary>>
    fun count(query: LibraryQuery): Flow<Int>

    fun recentlyAdded(kinds: Set<LibraryKind> = emptySet(), pageSize: Int = 30): Flow<PagingData<LibraryItemSummary>>
    fun favorites(pageSize: Int = 50): Flow<PagingData<LibraryItemSummary>>

    suspend fun getItem(itemId: Long): LibraryItemDetails?
    suspend fun versions(target: PlaybackTarget, includeUnavailable: Boolean = false): List<MediaVersion>

    suspend fun setFavorite(itemId: Long, favorite: Boolean)

    /** Adds a title to / removes it from "My library" (the Library tab). Its files are untouched. */
    suspend fun setInLibrary(itemId: Long, inLibrary: Boolean)

    /** Manual correction; locks the item so sync never renames it. */
    suspend fun updateItem(itemId: Long, title: String?, year: Int?): AppResult<Unit>

    /**
     * Removes an item from library lists. Its files stay linked to it (so it does not come back
     * on the next sync). With [keepUserData] = false its progress, favorite and preferences go too.
     */
    suspend fun deleteItem(itemId: Long, keepUserData: Boolean = true)
    suspend fun restoreItem(itemId: Long)

    /**
     * Merges [sourceId] into [targetId]: files, episodes, progress (furthest/most recent wins),
     * favorite, preferences and subtitles move; future files of the source go to the target.
     */
    suspend fun mergeItems(targetId: Long, sourceId: Long): AppResult<Unit>

    /** Puts a file on an item/episode by hand; sync will not move it again. */
    suspend fun linkMedia(mediaId: Long, target: PlaybackTarget): AppResult<Unit>

    /** Removes one file from the library (e.g. a wrong or broken version). */
    suspend fun excludeMedia(mediaId: Long): AppResult<Unit>

    /** Applies index changes to the library (insertions, updates, deletions). Incremental. */
    suspend fun sync(): LibrarySyncResult
}

interface MovieRepository {
    fun movies(query: LibraryQuery = LibraryQuery(), pageSize: Int = 50): Flow<PagingData<LibraryItemSummary>>
    suspend fun movie(itemId: Long): LibraryItemDetails?
}

interface SeriesRepository {
    fun series(query: LibraryQuery = LibraryQuery(), pageSize: Int = 50): Flow<PagingData<LibraryItemSummary>>
    suspend fun seriesDetails(seriesId: Long): LibraryItemDetails?
    fun seasons(seriesId: Long): Flow<List<Season>>
}

interface EpisodeRepository {
    /** Ordered by season then number; season-less numbering after the seasons. */
    fun episodes(seriesId: Long, seasonId: Long? = null, includeUnavailable: Boolean = false, pageSize: Int = 100): Flow<PagingData<Episode>>
    suspend fun episode(episodeId: Long): Episode?
    /** The episode after [episodeId] in watch order that has a file, or null. */
    suspend fun nextEpisode(episodeId: Long): Episode?
    /** The episode before [episodeId] in watch order that has a file, or null. */
    suspend fun previousEpisode(episodeId: Long): Episode?
    suspend fun versions(episodeId: Long): List<MediaVersion>
}

interface WatchHistoryRepository {
    /**
     * Records playback position (call every ~10 s and on pause/stop). Completion is decided
     * here (≥ 90 %, or the last 3 minutes of anything over 20 minutes).
     */
    suspend fun updateProgress(target: PlaybackTarget, mediaId: Long?, positionMs: Long, durationMs: Long): WatchProgress

    /** Movie/video/episode; a series target without episode marks every episode. */
    suspend fun markWatched(target: PlaybackTarget, watched: Boolean)
    suspend fun markSeasonWatched(seasonId: Long, watched: Boolean)
    suspend fun clearProgress(target: PlaybackTarget)

    fun progress(target: PlaybackTarget): Flow<WatchProgress?>
    fun continueWatching(limit: Int = 20): Flow<List<ContinueWatchingEntry>>
    fun recentlyWatched(pageSize: Int = 30): Flow<PagingData<ContinueWatchingEntry>>
    fun history(pageSize: Int = 50): Flow<PagingData<WatchHistoryEntry>>
}

interface SubtitleRepository {
    /** Tracks for a unit: Telegram subtitle files, local ones, and embedded tracks of [mediaId]. */
    fun subtitles(target: PlaybackTarget, mediaId: Long? = null): Flow<List<SubtitleTrack>>
    suspend fun addLocal(target: PlaybackTarget, path: String, language: String?, label: String?, format: String?): Long
    /** Called by the player once it has read the file's own tracks. */
    suspend fun replaceEmbedded(mediaId: Long, tracks: List<SubtitleTrack>)
    suspend fun remove(trackId: Long)
    suspend fun preference(itemId: Long): TrackPreference?
    suspend fun setSubtitlePreference(itemId: Long, language: String?, enabled: Boolean, offsetMs: Long = 0)
}

interface AudioTrackRepository {
    /** Probed tracks when known, else the ones guessed from the file name. */
    suspend fun tracks(mediaId: Long): List<AudioTrack>
    suspend fun replaceProbed(mediaId: Long, tracks: List<AudioTrack>)
    /** Languages available for an item across its files. */
    suspend fun languages(itemId: Long): List<String>
    suspend fun preferredLanguage(itemId: Long): String?
    suspend fun setPreferredLanguage(itemId: Long, language: String?)
}
