package dev.yourstream.watch.core

/**
 * Series → seasons → episodes → progress: which episode is "next".
 *
 * The **anchor** is the most recently watched regular episode (specials and stale partial
 * episodes do not steer). Then:
 *
 * 1. anchor unfinished → resume it (S01E05 = 100 %, S01E06 = 35 % → **S01E06**);
 * 2. anchor finished → the first unfinished, available episode after it
 *    (S01E06 = 100 % → **S01E07**), resumed if it has a position; skipped unavailable episodes
 *    are reported. Marked NEW_EPISODE when it arrived after the user watched the anchor;
 * 3. nothing unfinished after it, but the anchor was a re-watch → the next episode again (REWATCH);
 * 4. otherwise the first unfinished episode anywhere (EARLIER_GAP) or SERIES_FINISHED.
 *
 * Never watched → the first available episode. A special that is in progress and the most recent
 * thing watched is resumed; finished specials never move the pointer.
 */
object NextEpisodePolicy {
    fun decide(episodes: List<EpisodeProgress>): NextEpisodeDecision {
        if (episodes.isEmpty()) return NextEpisodeDecision(NextAction.NONE, NextReason.NO_EPISODES, null, null)
        val ordered = episodes.sortedWith(Progress.WATCH_ORDER)
        val regular = ordered.filter { !it.isSpecial }

        val latest = ordered.filter { it.started }.maxWithOrNull(RECENCY)
        if (latest != null && latest.isSpecial && !latest.completed && latest.available) return resume(latest, NextReason.IN_PROGRESS, latest)

        val anchor = regular.filter { it.started }.maxWithOrNull(RECENCY)
            ?: return first(regular.ifEmpty { ordered }, NextReason.NOT_STARTED, null)

        if (!anchor.completed && anchor.available) return resume(anchor, NextReason.IN_PROGRESS, anchor)

        val after = regular.drop(regular.indexOf(anchor) + 1)
        val skipped = mutableListOf<EpisodeProgress>()
        for (e in after) {
            if (e.completed) continue
            if (!e.available) { skipped += e; continue }
            val isNew = !e.started && e.addedAt != null && e.addedAt > (anchor.lastWatchedAt ?: 0)
            val reason = when {
                e.inProgress -> NextReason.IN_PROGRESS
                isNew -> NextReason.NEW_EPISODE
                else -> NextReason.NEXT_AFTER_COMPLETED
            }
            return resume(e, reason, anchor, skipped)
        }
        // Everything after the anchor is finished. A re-watched anchor (finished more than once)
        // means a re-watch is under way: continue it with the next episode not seen since.
        if (anchor.playCount >= 2) {
            after.firstOrNull { it.available && (it.lastWatchedAt ?: 0) < (anchor.lastWatchedAt ?: 0) }?.let {
                return NextEpisodeDecision(NextAction.START, NextReason.REWATCH, it, anchor, skipped)
            }
        }
        regular.firstOrNull { !it.completed && it.available }?.let { return resume(it, NextReason.EARLIER_GAP, anchor) }
        return NextEpisodeDecision(NextAction.NONE, NextReason.SERIES_FINISHED, null, anchor, skipped)
    }

    private fun resume(e: EpisodeProgress, reason: NextReason, anchor: EpisodeProgress?, skipped: List<EpisodeProgress> = emptyList()) =
        NextEpisodeDecision(if (e.inProgress) NextAction.RESUME else NextAction.START, reason, e, anchor, skipped)

    private fun first(list: List<EpisodeProgress>, reason: NextReason, anchor: EpisodeProgress?): NextEpisodeDecision {
        val skipped = list.takeWhile { !it.available }
        val e = list.firstOrNull { it.available } ?: return NextEpisodeDecision(NextAction.NONE, NextReason.NO_EPISODES, null, anchor)
        return NextEpisodeDecision(NextAction.START, reason, e, anchor, skipped)
    }

    /** Most recent first; equal times (bulk "mark watched") → the later episode. */
    private val RECENCY: Comparator<EpisodeProgress> = compareBy<EpisodeProgress> { it.lastWatchedAt ?: Long.MIN_VALUE }.then(Progress.WATCH_ORDER)
}
