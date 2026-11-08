package dev.yourstream.library.usecase

import androidx.paging.PagingData
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.ContinueWatchingEntry
import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.LibraryItemDetails
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MovieRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.Season
import dev.yourstream.library.api.SeriesRepository
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.library.api.WatchProgress
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchMedia @Inject constructor(private val media: MediaRepository) {
    operator fun invoke(query: LibraryQuery): Flow<PagingData<LibraryItemSummary>> = media.search(query)
    fun count(query: LibraryQuery): Flow<Int> = media.count(query)
}

class GetMovies @Inject constructor(private val movies: MovieRepository) {
    operator fun invoke(query: LibraryQuery = LibraryQuery()): Flow<PagingData<LibraryItemSummary>> = movies.movies(query)
}

class GetSeries @Inject constructor(private val series: SeriesRepository) {
    operator fun invoke(query: LibraryQuery = LibraryQuery()): Flow<PagingData<LibraryItemSummary>> = series.series(query)
    fun seasons(seriesId: Long): Flow<List<Season>> = series.seasons(seriesId)
}

class GetEpisodes @Inject constructor(private val episodes: EpisodeRepository) {
    operator fun invoke(seriesId: Long, seasonId: Long? = null): Flow<PagingData<Episode>> = episodes.episodes(seriesId, seasonId)
}

class GetItemDetails @Inject constructor(private val media: MediaRepository) {
    suspend operator fun invoke(itemId: Long): LibraryItemDetails? = media.getItem(itemId)
}

class UpdateWatchProgress @Inject constructor(private val watch: WatchHistoryRepository) {
    suspend operator fun invoke(target: PlaybackTarget, mediaId: Long?, positionMs: Long, durationMs: Long): WatchProgress =
        watch.updateProgress(target, mediaId, positionMs, durationMs)
}

class MarkAsWatched @Inject constructor(private val watch: WatchHistoryRepository) {
    suspend operator fun invoke(target: PlaybackTarget, watched: Boolean = true) = watch.markWatched(target, watched)
    suspend fun season(seasonId: Long, watched: Boolean = true) = watch.markSeasonWatched(seasonId, watched)
}

class GetContinueWatching @Inject constructor(private val watch: WatchHistoryRepository) {
    operator fun invoke(limit: Int = 20): Flow<List<ContinueWatchingEntry>> = watch.continueWatching(limit)
}

class GetRecentlyAdded @Inject constructor(private val media: MediaRepository) {
    operator fun invoke(kinds: Set<LibraryKind> = emptySet()): Flow<PagingData<LibraryItemSummary>> = media.recentlyAdded(kinds)
}

class GetRecentlyWatched @Inject constructor(private val watch: WatchHistoryRepository) {
    operator fun invoke(): Flow<PagingData<ContinueWatchingEntry>> = watch.recentlyWatched()
}

class GetFavorites @Inject constructor(private val media: MediaRepository) {
    operator fun invoke(): Flow<PagingData<LibraryItemSummary>> = media.favorites()
}

class ToggleFavorite @Inject constructor(private val media: MediaRepository) {
    suspend operator fun invoke(itemId: Long, favorite: Boolean) = media.setFavorite(itemId, favorite)
}

class MergeMedia @Inject constructor(private val media: MediaRepository) {
    suspend operator fun invoke(targetId: Long, sourceId: Long): AppResult<Unit> = media.mergeItems(targetId, sourceId)
}

class SyncLibrary @Inject constructor(private val media: MediaRepository) {
    suspend operator fun invoke() = media.sync()
}
