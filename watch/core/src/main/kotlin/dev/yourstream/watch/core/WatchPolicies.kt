package dev.yourstream.watch.core

/** Watch state of a whole item, from its unit(s). */
data class ItemProgress(
    val status: WatchStatus,
    /** 0..100: a movie's position; for a series the share of available regular episodes watched (partials count fractionally). */
    val percent: Int,
    val completedEpisodes: Int = 0,
    val partialEpisodes: Int = 0,
    val totalEpisodes: Int = 0,
    /** Times finished (movie) / episode completions (series). */
    val watchCount: Int = 0,
    val abandoned: AbandonReason? = null,
)

object WatchPolicies {
    private const val DAY = 24 * 3600 * 1000L

    /** Movie / video / single unit. [lastWatchedAt] null = never played. */
    fun movie(
        positionMs: Long, durationMs: Long, completed: Boolean, playCount: Int, lastWatchedAt: Long?,
        dismissedAt: Long?, now: Long, policy: WatchPolicy = WatchPolicy(),
    ): ItemProgress {
        val percent = Progress.percent(positionMs, durationMs, completed)
        val inProgress = !completed && positionMs > 0
        val abandoned = if (inProgress || (lastWatchedAt != null && !completed && playCount == 0)) {
            abandonment(lastWatchedAt, dismissedAt, now, policy.abandonMovieAfterDays, early = percent < policy.earlyDropPercent, policy)
        } else null
        val status = when {
            abandoned != null -> WatchStatus.ABANDONED
            inProgress -> WatchStatus.IN_PROGRESS
            completed || playCount > 0 -> WatchStatus.COMPLETED
            else -> WatchStatus.UNWATCHED
        }
        return ItemProgress(status, percent, watchCount = playCount, abandoned = abandoned)
    }

    fun series(episodes: List<EpisodeProgress>, dismissedAt: Long?, now: Long, policy: WatchPolicy = WatchPolicy()): ItemProgress {
        val regular = episodes.filter { !it.isSpecial }
        val counted = regular.filter { it.available || it.started }
        val completed = counted.count { it.completed }
        val partial = counted.count { it.inProgress }
        val total = counted.size
        val percent = if (total == 0) 0 else ((completed * 100.0 + counted.filter { it.inProgress }.sumOf { it.percent.toDouble() }) / total).toInt().coerceIn(0, 100)
        val lastWatched = episodes.mapNotNull { it.lastWatchedAt }.maxOrNull()
        val finished = total > 0 && completed == total
        val started = lastWatched != null
        val abandoned = if (started && !finished) {
            abandonment(lastWatched, dismissedAt, now, policy.abandonSeriesAfterDays, early = completed <= 1 && regular.size > 2, policy)
        } else null
        val status = when {
            finished -> WatchStatus.COMPLETED
            abandoned != null -> WatchStatus.ABANDONED
            started -> WatchStatus.IN_PROGRESS
            else -> WatchStatus.UNWATCHED
        }
        return ItemProgress(status, if (finished) 100 else percent.coerceAtMost(99), completed, partial, total, episodes.sumOf { it.playCount }, abandoned)
    }

    /**
     * DISMISSED while the user has not played it since dismissing; INACTIVE after [days] idle;
     * DROPPED_EARLY after a shorter idle time when they stopped [early].
     */
    fun abandonment(lastWatchedAt: Long?, dismissedAt: Long?, now: Long, days: Int, early: Boolean, policy: WatchPolicy): AbandonReason? {
        if (dismissedAt != null && (lastWatchedAt == null || lastWatchedAt <= dismissedAt)) return AbandonReason.DISMISSED
        val idle = now - (lastWatchedAt ?: return null)
        return when {
            idle >= days * DAY -> AbandonReason.INACTIVE
            early && idle >= policy.earlyDropAfterDays * DAY -> AbandonReason.DROPPED_EARLY
            else -> null
        }
    }
}

/** One Continue Watching candidate before ordering. */
data class ContinueCandidate<T>(
    val value: T,
    /** When the user last touched the item; a new episode counts from its arrival. */
    val activityAt: Long,
    val action: NextAction,
    val percent: Int,
)

object ContinueWatchingOrder {
    /**
     * Most recent activity first. At equal activity a resume beats a fresh start (and more
     * progress beats less), so a half-watched episode never sinks below a "next".
     */
    fun <T> rank(candidates: List<ContinueCandidate<T>>, limit: Int): List<T> = candidates
        .filter { it.action != NextAction.NONE }
        .sortedWith(compareByDescending<ContinueCandidate<T>> { it.activityAt }.thenBy { it.action != NextAction.RESUME }.thenByDescending { it.percent })
        .take(limit)
        .map { it.value }
}
