package dev.yourstream.watch.engine

import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.watch.core.AbandonReason
import dev.yourstream.watch.core.EpisodeProgress
import dev.yourstream.watch.core.ItemProgress
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.core.NextEpisodeDecision
import dev.yourstream.watch.core.NextReason
import dev.yourstream.watch.core.WatchStatus
import kotlinx.coroutines.flow.Flow

/**
 * What the app knows about the user's viewing: per item and per episode progress, and the lists
 * built from it. All lists are live (they re-emit when progress, favorites or the library change).
 */
interface WatchIntelligence {
    /** Things to resume, plus the next episode of every series in progress; abandoned content excluded. */
    fun continueWatching(limit: Int = 20): Flow<List<ContinueEntry>>

    /** One row per item, most recently watched first, with its last unit. */
    fun recentlyWatched(limit: Int = 30): Flow<List<RecentEntry>>

    /** Newest arrivals; started series show how many new unwatched episodes arrived. */
    fun recentlyAdded(limit: Int = 30, kinds: Set<LibraryKind> = emptySet()): Flow<List<AddedEntry>>

    fun favorites(): Flow<List<WatchSummary>>

    /** Started but given up (idle for long, dropped early, or removed by the user). */
    fun abandoned(limit: Int = 50): Flow<List<WatchSummary>>

    /** The next episode of a series (see NextEpisodePolicy). */
    suspend fun nextEpisode(seriesId: Long): NextEpisodeDecision

    /** Every tracked fact about one item. */
    suspend fun summary(itemId: Long): WatchSummary?

    /** Episode-level progress of a series, in watch order. */
    suspend fun episodes(seriesId: Long): List<EpisodeProgress>

    /** "Remove from Continue Watching": stays out until it is played again. */
    suspend fun dismiss(itemId: Long)
    suspend fun restore(itemId: Long)
}

data class ContinueEntry(
    val target: PlaybackTarget,
    val kind: LibraryKind,
    val title: String,
    val year: Int?,
    /** Episode details for series entries. */
    val episode: EpisodeProgress?,
    val action: NextAction,
    /** IN_PROGRESS for resumes; NEXT_AFTER_COMPLETED / NEW_EPISODE / … for series. */
    val reason: NextReason,
    val positionMs: Long,
    val durationMs: Long,
    /** Completion of this unit, 0..100. */
    val percent: Int,
    /** Completion of the whole series, 0..100 (series only). */
    val seriesPercent: Int?,
    val activityAt: Long,
    val lastMediaId: Long?,
)

data class RecentEntry(
    val summary: WatchSummary,
    /** The unit watched last (an episode for series). */
    val lastEpisode: EpisodeProgress?,
    val lastWatchedAt: Long,
)

data class AddedEntry(
    val itemId: Long,
    val kind: LibraryKind,
    val title: String,
    val year: Int?,
    val addedAt: Long,
    val status: WatchStatus,
    /** Unwatched episodes that arrived after the user last watched the series. */
    val newEpisodes: Int,
    val favorite: Boolean,
)

data class WatchSummary(
    val itemId: Long,
    val kind: LibraryKind,
    val title: String,
    val year: Int?,
    val status: WatchStatus,
    /** 0..100 (movie: position; series: episodes watched). */
    val percent: Int,
    /** Resume point of a movie/video (0 when finished or not started). */
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long?,
    /** Times finished (movie) / episode completions (series). */
    val watchCount: Int,
    /** Viewing sessions in the history log. */
    val sessions: Int,
    val completedEpisodes: Int,
    val partialEpisodes: Int,
    val totalEpisodes: Int,
    val favorite: Boolean,
    val abandoned: AbandonReason?,
    /** Series only. */
    val next: NextEpisodeDecision?,
) {
    companion object {
        internal fun of(
            id: Long, kind: LibraryKind, title: String, year: Int?, p: ItemProgress, positionMs: Long, durationMs: Long,
            lastWatchedAt: Long?, sessions: Int, favorite: Boolean, next: NextEpisodeDecision?,
        ) = WatchSummary(id, kind, title, year, p.status, p.percent, positionMs, durationMs, lastWatchedAt, p.watchCount, sessions,
            p.completedEpisodes, p.partialEpisodes, p.totalEpisodes, favorite, p.abandoned, next)
    }
}
