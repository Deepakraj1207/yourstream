package dev.yourstream.watch.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPoliciesTest {
    private val day = 24 * 3600 * 1000L
    private val now = 1_000 * day

    @Test
    fun movieStatusAndAbandonment() {
        WatchPolicies.movie(0, 0, false, 0, null, null, now).let { assertEquals(WatchStatus.UNWATCHED, it.status); assertEquals(0, it.percent) }
        WatchPolicies.movie(60, 100, false, 0, now - day, null, now).let { assertEquals(WatchStatus.IN_PROGRESS, it.status); assertEquals(60, it.percent) }
        WatchPolicies.movie(0, 100, true, 2, now - 400 * day, null, now).let {
            assertEquals(WatchStatus.COMPLETED, it.status); assertEquals(100, it.percent); assertEquals(2, it.watchCount); assertNull(it.abandoned, "finished is never abandoned")
        }
        assertEquals(AbandonReason.INACTIVE, WatchPolicies.movie(60, 100, false, 0, now - 31 * day, null, now).abandoned)
        assertEquals(AbandonReason.DROPPED_EARLY, WatchPolicies.movie(5, 100, false, 0, now - 15 * day, null, now).abandoned)
        assertNull(WatchPolicies.movie(60, 100, false, 0, now - 15 * day, null, now).abandoned, "60 % two weeks ago: still going")
        // Dismissed until played again.
        assertEquals(AbandonReason.DISMISSED, WatchPolicies.movie(60, 100, false, 0, now - day, now - 1, now).abandoned)
        assertNull(WatchPolicies.movie(60, 100, false, 0, now, now - 1, now).abandoned)
    }

    @Test
    fun seriesCounts() {
        val eps = listOf(
            EpisodeProgress(1, 1, 1, completed = true, playCount = 1, lastWatchedAt = now - day),
            EpisodeProgress(2, 1, 2, completed = true, playCount = 2, lastWatchedAt = now - day),
            EpisodeProgress(3, 1, 3, positionMs = 50, durationMs = 100, lastWatchedAt = now - day),
            EpisodeProgress(4, 1, 4),
            EpisodeProgress(5, 0, 1, completed = true, playCount = 1, lastWatchedAt = now), // special: not counted
            EpisodeProgress(6, 1, 5, available = false), // no file, never watched: not counted
        )
        val p = WatchPolicies.series(eps, null, now)
        assertEquals(WatchStatus.IN_PROGRESS, p.status)
        assertEquals(2 to 1, p.completedEpisodes to p.partialEpisodes)
        assertEquals(4, p.totalEpisodes)
        assertEquals(62, p.percent, "(2 × 100 + 50) / 4")
        assertEquals(4, p.watchCount)
        val done = WatchPolicies.series(eps.filter { it.completed }, null, now)
        assertEquals(WatchStatus.COMPLETED, done.status); assertEquals(100, done.percent)
        assertEquals(AbandonReason.INACTIVE, WatchPolicies.series(eps.map { it.copy(lastWatchedAt = it.lastWatchedAt?.let { now - 61 * day }) }, null, now).abandoned)
        // One episode of a long series, two weeks ago: dropped early.
        val one = (1..10).map { EpisodeProgress(it.toLong(), 1, it) }.map { if (it.number == 1) it.copy(completed = true, lastWatchedAt = now - 15 * day) else it }
        assertEquals(AbandonReason.DROPPED_EARLY, WatchPolicies.series(one, null, now).abandoned)
        assertEquals(WatchStatus.UNWATCHED, WatchPolicies.series(eps.map { it.copy(lastWatchedAt = null, completed = false, positionMs = 0) }, null, now).status)
    }

    @Test
    fun continueWatchingOrder() {
        val c = listOf(
            ContinueCandidate("old resume", 10, NextAction.RESUME, 50),
            ContinueCandidate("new next", 30, NextAction.START, 0),
            ContinueCandidate("new resume", 30, NextAction.RESUME, 20),
            ContinueCandidate("finished", 40, NextAction.NONE, 0),
        )
        assertEquals(listOf("new resume", "new next", "old resume"), ContinueWatchingOrder.rank(c, 10))
        assertEquals(listOf("new resume"), ContinueWatchingOrder.rank(c, 1))
    }
}
