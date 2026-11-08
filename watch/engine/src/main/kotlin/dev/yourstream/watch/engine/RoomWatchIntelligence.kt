package dev.yourstream.watch.engine

import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.dao.WatchEpisodeRow
import dev.yourstream.core.database.dao.WatchItemRow
import dev.yourstream.core.database.entity.WatchDismissalEntity
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.watch.core.ContinueCandidate
import dev.yourstream.watch.core.ContinueWatchingOrder
import dev.yourstream.watch.core.EpisodeProgress
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.core.NextEpisodeDecision
import dev.yourstream.watch.core.NextEpisodePolicy
import dev.yourstream.watch.core.NextReason
import dev.yourstream.watch.core.Progress
import dev.yourstream.watch.core.WatchPolicies
import dev.yourstream.watch.core.WatchPolicy
import dev.yourstream.watch.core.WatchStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the watch lists from Room on every relevant change. The heavy part, next-episode
 * decisions, needs the episodes of *started* series only (loaded in one query per chunk).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RoomWatchIntelligence @Inject constructor(
    db: YourStreamDatabase,
    private val clock: Clock,
) : WatchIntelligence {
    private val dao = db.watchIntelligenceDao()
    var policy = WatchPolicy()

    /** One item with everything derived from it. */
    private inner class Facts(val row: WatchItemRow, val episodes: List<EpisodeProgress>) {
        val kind = runCatching { LibraryKind.valueOf(row.kind) }.getOrDefault(LibraryKind.VIDEO)
        val isSeries = kind == LibraryKind.SERIES
        val next: NextEpisodeDecision? = if (isSeries) NextEpisodePolicy.decide(episodes) else null
        val progress = if (isSeries) WatchPolicies.series(episodes, row.dismissedAt, clock.nowMillis(), policy)
        else WatchPolicies.movie(row.positionMs ?: 0, row.durationMs ?: 0, row.completed, row.playCount, row.lastWatchedAt, row.dismissedAt, clock.nowMillis(), policy)

        val summary get() = WatchSummary.of(
            row.id, kind, row.title, row.year, progress, if (isSeries) 0 else row.positionMs ?: 0, if (isSeries) 0 else row.durationMs ?: 0,
            row.lastWatchedAt, row.sessions, row.favoriteAt != null, next,
        )
    }

    private suspend fun facts(ids: List<Long>): List<Facts> {
        if (ids.isEmpty()) return emptyList()
        val rows = ids.chunked(500).flatMap { dao.items(it) }.associateBy { it.id }
        val seriesIds = rows.values.filter { it.kind == LibraryKind.SERIES.name }.map { it.id }
        val episodes = seriesIds.chunked(500).flatMap { dao.episodes(it) }.groupBy { it.seriesId }
        return ids.mapNotNull { id -> rows[id]?.let { Facts(it, episodes[id].orEmpty().map { e -> e.toProgress() }) } }
    }

    private fun <T> live(block: suspend () -> T): Flow<T> = dao.changes().conflate().mapLatest { block() }

    // ------------------------------------------------------------------ lists

    override fun continueWatching(limit: Int): Flow<List<ContinueEntry>> = live { continueNow(limit) }

    suspend fun continueNow(limit: Int = 20): List<ContinueEntry> {
        val candidates = facts(dao.watchedItemIds(MAX_TRACKED)).filter { !it.row.hidden && it.progress.abandoned == null }.mapNotNull { f ->
            if (f.isSeries) seriesEntry(f) else movieEntry(f)
        }
        return ContinueWatchingOrder.rank(candidates, limit)
    }

    private fun movieEntry(f: Facts): ContinueCandidate<ContinueEntry>? {
        val r = f.row
        if (r.completed || (r.positionMs ?: 0) <= 0 || r.availableCount <= 0) return null
        val entry = ContinueEntry(
            PlaybackTarget(r.id), f.kind, r.title, r.year, null, NextAction.RESUME, NextReason.IN_PROGRESS, r.positionMs ?: 0, r.durationMs ?: 0,
            f.progress.percent, null, r.lastWatchedAt ?: 0, r.lastMediaId,
        )
        return ContinueCandidate(entry, entry.activityAt, NextAction.RESUME, entry.percent)
    }

    private fun seriesEntry(f: Facts): ContinueCandidate<ContinueEntry>? {
        val d = f.next ?: return null
        val e = d.episode ?: return null
        if (d.action == NextAction.NONE || d.reason == NextReason.NOT_STARTED) return null
        // A new episode is "activity" from the moment it arrived.
        val activity = maxOf(f.row.lastWatchedAt ?: 0, if (d.reason == NextReason.NEW_EPISODE) e.addedAt ?: 0 else 0)
        val entry = ContinueEntry(
            PlaybackTarget(f.row.id, e.episodeId), f.kind, f.row.title, f.row.year, e, d.action, d.reason, e.positionMs, e.durationMs,
            e.percent, f.progress.percent, activity, e.lastMediaId,
        )
        return ContinueCandidate(entry, activity, d.action, e.percent)
    }

    override fun recentlyWatched(limit: Int): Flow<List<RecentEntry>> = live { recentlyWatchedNow(limit) }

    suspend fun recentlyWatchedNow(limit: Int = 30): List<RecentEntry> =
        facts(dao.watchedItemIds(limit * 2)).filter { !it.row.hidden && it.row.lastWatchedAt != null }.take(limit).map { f ->
            RecentEntry(f.summary, f.episodes.filter { it.started }.maxByOrNull { it.lastWatchedAt ?: 0 }, f.row.lastWatchedAt ?: 0)
        }

    override fun recentlyAdded(limit: Int, kinds: Set<LibraryKind>): Flow<List<AddedEntry>> = live { recentlyAddedNow(limit, kinds) }

    suspend fun recentlyAddedNow(limit: Int = 30, kinds: Set<LibraryKind> = emptySet()): List<AddedEntry> =
        facts(dao.recentlyAddedIds(limit, kinds.isEmpty(), kinds.map { it.name })).map { f ->
            val lastWatched = f.row.lastWatchedAt
            val newEpisodes = if (f.isSeries && lastWatched != null) {
                f.episodes.count { !it.started && it.available && !it.isSpecial && (it.addedAt ?: 0) > lastWatched }
            } else 0
            AddedEntry(f.row.id, f.kind, f.row.title, f.row.year, f.row.latestAddedAt ?: f.row.addedAt, f.progress.status, newEpisodes, f.row.favoriteAt != null)
        }

    override fun favorites(): Flow<List<WatchSummary>> = live { favoritesNow() }

    suspend fun favoritesNow(): List<WatchSummary> = facts(dao.favoriteIds()).filter { !it.row.hidden }.map { it.summary }

    override fun abandoned(limit: Int): Flow<List<WatchSummary>> = live { abandonedNow(limit) }

    suspend fun abandonedNow(limit: Int = 50): List<WatchSummary> =
        facts(dao.watchedItemIds(MAX_TRACKED)).filter { !it.row.hidden && it.progress.abandoned != null }.take(limit).map { it.summary }

    // ------------------------------------------------------------------ single items

    override suspend fun nextEpisode(seriesId: Long): NextEpisodeDecision = NextEpisodePolicy.decide(episodes(seriesId))

    override suspend fun summary(itemId: Long): WatchSummary? = facts(listOf(itemId)).firstOrNull()?.summary

    override suspend fun episodes(seriesId: Long): List<EpisodeProgress> = dao.episodes(listOf(seriesId)).map { it.toProgress() }.sortedWith(Progress.WATCH_ORDER)

    override suspend fun dismiss(itemId: Long) = dao.dismiss(WatchDismissalEntity(itemId, clock.nowMillis()))

    override suspend fun restore(itemId: Long) = dao.restore(itemId)

    private fun WatchEpisodeRow.toProgress() = EpisodeProgress(
        episodeId = id, season = seasonNumber, number = number, numberEnd = numberEnd, title = title, available = availableCount > 0,
        addedAt = latestAddedAt, positionMs = positionMs ?: 0, durationMs = durationMs ?: 0, completed = completed, playCount = playCount,
        lastWatchedAt = lastWatchedAt, lastMediaId = lastMediaId,
    )

    private companion object {
        /** Items considered for Continue Watching / Abandoned (most recently watched first). */
        const val MAX_TRACKED = 500
    }
}

/** Status filter helper for callers. */
fun List<WatchSummary>.withStatus(status: WatchStatus) = filter { it.status == status }
