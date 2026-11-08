package dev.yourstream.library.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.err
import dev.yourstream.core.common.ok
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.FavoriteEntity
import dev.yourstream.core.database.entity.LibraryAliasEntity
import dev.yourstream.core.database.entity.MediaLinkEntity
import dev.yourstream.core.database.entity.SeasonEntity
import dev.yourstream.core.database.entity.TrackPreferenceEntity
import dev.yourstream.core.database.entity.WatchStateEntity
import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.LibraryItemDetails
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.LibrarySyncResult
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MediaVersion
import dev.yourstream.library.api.MovieRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.Season
import dev.yourstream.library.api.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private fun pager(pageSize: Int) = PagingConfig(pageSize = pageSize, enablePlaceholders = false, maxSize = pageSize * 8)

@Singleton
class RoomMediaRepository @Inject constructor(
    private val db: YourStreamDatabase,
    private val synchronizer: LibrarySynchronizer,
    private val watch: RoomWatchHistoryRepository,
    private val clock: Clock,
) : MediaRepository {

    private val dao = db.libraryDao()
    private val watchDao = db.watchDao()
    private val trackDao = db.trackDao()

    override fun search(query: LibraryQuery, pageSize: Int): Flow<PagingData<LibraryItemSummary>> {
        val sql = LibraryQueryBuilder.list(query)
        return Pager(pager(pageSize)) { dao.itemsPaged(sql.query()) }.flow.map { it.map { row -> row.toModel() } }
    }

    override fun count(query: LibraryQuery): Flow<Int> = dao.count(LibraryQueryBuilder.count(query).query())

    override fun recentlyAdded(kinds: Set<LibraryKind>, pageSize: Int) =
        search(LibraryQuery(kinds = kinds, sort = LibrarySort.RECENTLY_ADDED), pageSize)

    override fun favorites(pageSize: Int): Flow<PagingData<LibraryItemSummary>> {
        val sql = LibraryQueryBuilder.list(LibraryQuery(includeUnavailable = true), favoritesOrder = true)
        return Pager(pager(pageSize)) { dao.itemsPaged(sql.query()) }.flow.map { it.map { row -> row.toModel() } }
    }

    override suspend fun getItem(itemId: Long): LibraryItemDetails? {
        val item = dao.item(itemId) ?: return null
        val row = dao.items(LibraryQueryBuilder.byId(itemId).query()).firstOrNull() ?: return null
        val isSeries = item.kind == LibraryKind.SERIES.name
        val versions = dao.versions(itemId, null, includeUnavailable = true).map { it.toModel() }
            .let { all -> if (isSeries) all.filter { it.episodeId == null } else all }
        return LibraryItemDetails(
            summary = row.toModel(),
            normalizedTitle = item.normalizedTitle,
            locked = item.locked,
            hidden = item.hidden,
            languages = dao.languages(itemId),
            versions = versions,
            seasons = if (isSeries) dao.seasons(itemId).map { it.toModel() } else emptyList(),
        )
    }

    override suspend fun versions(target: PlaybackTarget, includeUnavailable: Boolean): List<MediaVersion> =
        dao.versions(target.itemId, target.episodeId, includeUnavailable).map { it.toModel() }

    override suspend fun setFavorite(itemId: Long, favorite: Boolean) {
        if (favorite) dao.addFavorite(FavoriteEntity(itemId, clock.nowMillis())) else dao.removeFavorite(itemId)
    }

    override suspend fun updateItem(itemId: Long, title: String?, year: Int?): AppResult<Unit> {
        val item = dao.item(itemId) ?: return AppError.NotFound("item $itemId").err()
        val newTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: item.title
        dao.updateItem(
            item.copy(
                title = newTitle, sortTitle = LibraryQueryBuilder.sortTitle(newTitle), year = year ?: item.year,
                // Keep the old key searchable too; the match key (identity) never changes.
                searchText = (item.searchText + " " + LibraryQueryBuilder.normalize(newTitle)).trim(),
                locked = true, updatedAt = clock.nowMillis(),
            ),
        )
        return Unit.ok()
    }

    override suspend fun deleteItem(itemId: Long, keepUserData: Boolean) {
        db.withTransaction {
            dao.setHidden(itemId, true, clock.nowMillis())
            if (!keepUserData) {
                dao.removeFavorite(itemId)
                watchDao.statesOf(itemId).forEach { watchDao.deleteState(it.itemId, it.episodeKey) }
                trackDao.deletePreference(itemId)
                watchDao.setItemWatchAggregates(itemId, 0, null)
            }
        }
    }

    override suspend fun restoreItem(itemId: Long) = dao.setHidden(itemId, false, clock.nowMillis())

    override suspend fun mergeItems(targetId: Long, sourceId: Long): AppResult<Unit> {
        if (targetId == sourceId) return AppError.InvalidInput("SAME_ITEM", "Cannot merge an item into itself").err()
        val target = dao.item(targetId) ?: return AppError.NotFound("item $targetId").err()
        val source = dao.item(sourceId) ?: return AppError.NotFound("item $sourceId").err()
        val compatible = target.kind == source.kind ||
            (source.kind == LibraryKind.VIDEO.name && target.kind == LibraryKind.MOVIE.name) ||
            (source.kind == LibraryKind.MOVIE.name && target.kind == LibraryKind.VIDEO.name)
        if (!compatible) return AppError.InvalidInput("KIND_MISMATCH", "Cannot merge a ${source.kind} into a ${target.kind}").err()

        db.withTransaction {
            if (source.kind == LibraryKind.SERIES.name) mergeEpisodes(targetId, sourceId)
            mergeWatchState(sourceId, 0L, targetId, 0L)
            dao.moveLinks(sourceId, targetId)
            if (dao.isFavorite(sourceId) && !dao.isFavorite(targetId)) dao.addFavorite(FavoriteEntity(targetId, clock.nowMillis()))
            if (trackDao.preference(targetId) == null) {
                trackDao.preference(sourceId)?.let { trackDao.upsertPreference(it.copy(itemId = targetId)) }
            }
            trackDao.moveSubtitles(sourceId, targetId)
            watchDao.moveHistory(sourceId, targetId)
            // Future files of the source (its match key) now land in the target.
            dao.repointAliases(sourceId, targetId)
            dao.upsertAlias(LibraryAliasEntity(source.matchKey, targetId))
            dao.updateItem(target.copy(locked = true, updatedAt = clock.nowMillis()))
            dao.deleteItem(sourceId)
            synchronizer.recompute(targetId)
            watch.refreshAggregates(targetId)
        }
        return Unit.ok()
    }

    private suspend fun mergeEpisodes(targetId: Long, sourceId: Long) {
        for (episode in dao.episodesOf(sourceId)) {
            val seasonId = dao.seasonId(targetId, episode.seasonNumber)
                ?: dao.insertSeason(SeasonEntity(seriesId = targetId, number = episode.seasonNumber, title = null))
            val existing = dao.episodeId(seasonId, episode.number)
            if (existing != null) {
                dao.moveEpisodeLinks(episode.id, existing)
                trackDao.moveEpisodeSubtitles(episode.id, existing)
                mergeWatchState(sourceId, episode.id, targetId, existing)
                watchDao.moveEpisodeHistory(sourceId, episode.id, targetId, existing)
                dao.deleteEpisode(episode.id)
            } else {
                dao.moveEpisode(episode.id, targetId, seasonId, episode.seasonNumber)
                mergeWatchState(sourceId, episode.id, targetId, episode.id)
            }
        }
    }

    /** Keeps whichever progress says more: completed beats not, then the most recent. */
    private suspend fun mergeWatchState(sourceItem: Long, sourceKey: Long, targetItem: Long, targetKey: Long) {
        val from = watchDao.state(sourceItem, sourceKey) ?: return
        val into = watchDao.state(targetItem, targetKey)
        val merged = when {
            into == null -> from
            from.completed != into.completed -> if (from.completed) from else into
            else -> if (from.lastWatchedAt >= into.lastWatchedAt) from else into
        }.copy(itemId = targetItem, episodeKey = targetKey, playCount = from.playCount + (into?.playCount ?: 0))
        watchDao.deleteState(sourceItem, sourceKey)
        watchDao.upsertState(merged)
    }

    override suspend fun linkMedia(mediaId: Long, target: PlaybackTarget): AppResult<Unit> {
        dao.item(target.itemId) ?: return AppError.NotFound("item ${target.itemId}").err()
        target.episodeId?.let { e -> if (dao.episode(e)?.seriesId != target.itemId) return AppError.InvalidInput("EPISODE", "Episode is not part of the item").err() }
        db.withTransaction {
            val old = dao.link(mediaId)
            dao.upsertLink(MediaLinkEntity(mediaId, target.itemId, target.episodeId, LibrarySynchronizer.METHOD_MANUAL, "manual", clock.nowMillis()))
            old?.let { if (it.itemId != target.itemId) synchronizer.recompute(it.itemId) }
            synchronizer.recompute(target.itemId)
        }
        return Unit.ok()
    }

    override suspend fun excludeMedia(mediaId: Long): AppResult<Unit> {
        val link = dao.link(mediaId) ?: return AppError.NotFound("media $mediaId is not in the library").err()
        db.withTransaction {
            dao.upsertLink(link.copy(method = LibrarySynchronizer.METHOD_EXCLUDED, linkedAt = clock.nowMillis()))
            synchronizer.recompute(link.itemId)
        }
        return Unit.ok()
    }

    override suspend fun sync(): LibrarySyncResult = synchronizer.sync()
}

@Singleton
class RoomMovieRepository @Inject constructor(private val media: RoomMediaRepository) : MovieRepository {
    override fun movies(query: LibraryQuery, pageSize: Int) = media.search(query.copy(kinds = setOf(LibraryKind.MOVIE)), pageSize)
    override suspend fun movie(itemId: Long) = media.getItem(itemId)?.takeIf { it.summary.kind == LibraryKind.MOVIE }
}

@Singleton
class RoomSeriesRepository @Inject constructor(
    db: YourStreamDatabase,
    private val media: RoomMediaRepository,
) : SeriesRepository {
    private val dao = db.libraryDao()
    override fun series(query: LibraryQuery, pageSize: Int) = media.search(query.copy(kinds = setOf(LibraryKind.SERIES)), pageSize)
    override suspend fun seriesDetails(seriesId: Long) = media.getItem(seriesId)?.takeIf { it.summary.kind == LibraryKind.SERIES }
    override fun seasons(seriesId: Long): Flow<List<Season>> = dao.observeSeasons(seriesId).map { list -> list.map { it.toModel() } }
}

@Singleton
class RoomEpisodeRepository @Inject constructor(db: YourStreamDatabase) : EpisodeRepository {
    private val dao = db.libraryDao()

    override fun episodes(seriesId: Long, seasonId: Long?, includeUnavailable: Boolean, pageSize: Int): Flow<PagingData<Episode>> =
        Pager(pager(pageSize)) { dao.episodesPaged(seriesId, seasonId, includeUnavailable) }.flow.map { it.map { row -> row.toModel() } }

    override suspend fun episode(episodeId: Long): Episode? = dao.episodeRow(episodeId)?.toModel()

    override suspend fun nextEpisode(episodeId: Long): Episode? {
        val current = dao.episode(episodeId) ?: return null
        val order = if (current.seasonNumber < 0) 1000 else current.seasonNumber
        val next = dao.nextEpisode(current.seriesId, order, current.number) ?: return null
        return episode(next.id)
    }

    override suspend fun versions(episodeId: Long): List<MediaVersion> {
        val episode = dao.episode(episodeId) ?: return emptyList()
        return dao.versions(episode.seriesId, episodeId, includeUnavailable = false).map { it.toModel() }
    }
}
