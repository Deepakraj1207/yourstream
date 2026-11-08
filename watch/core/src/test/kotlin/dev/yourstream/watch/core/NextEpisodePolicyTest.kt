package dev.yourstream.watch.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextEpisodePolicyTest {
    private val MIN = 60_000L
    private val DUR = 22 * MIN
    private var nextId = 1L

    /** A season of [n] episodes. */
    private fun season(s: Int, n: Int) = (1..n).map { EpisodeProgress(nextId++, s, it, durationMs = 0) }

    private fun List<EpisodeProgress>.watch(label: String, percent: Int, at: Long, plays: Int? = null): List<EpisodeProgress> = map {
        if (it.label != label) it
        else if (percent >= 100) it.copy(completed = true, positionMs = 0, durationMs = DUR, playCount = plays ?: (it.playCount + 1), lastWatchedAt = at)
        else it.copy(completed = false, positionMs = DUR * percent / 100, durationMs = DUR, playCount = plays ?: it.playCount, lastWatchedAt = at)
    }

    private fun List<EpisodeProgress>.set(label: String, f: (EpisodeProgress) -> EpisodeProgress) = map { if (it.label == label) f(it) else it }

    private fun next(eps: List<EpisodeProgress>) = NextEpisodePolicy.decide(eps)

    @Test
    fun specExample() {
        // S01E05 = 100 %, S01E06 = 35 % → continue S01E06.
        var eps = season(1, 10)
        for (e in 1..5) eps = eps.watch("S01E%02d".format(e), 100, at = e * 1000L)
        eps = eps.watch("S01E06", 35, at = 6_000)
        next(eps).let {
            assertEquals("S01E06", it.episode?.label)
            assertEquals(NextAction.RESUME, it.action)
            assertEquals(NextReason.IN_PROGRESS, it.reason)
            assertEquals(35, it.episode?.percent)
        }
        // S01E06 = 100 % → S01E07, from the beginning.
        eps = eps.watch("S01E06", 100, at = 7_000)
        next(eps).let {
            assertEquals("S01E07", it.episode?.label)
            assertEquals(NextAction.START, it.action)
            assertEquals(NextReason.NEXT_AFTER_COMPLETED, it.reason)
        }
    }

    @Test
    fun notStartedSeasonBoundaryAndFinish() {
        val eps = season(1, 3) + season(2, 2)
        next(eps).let { assertEquals("S01E01", it.episode?.label); assertEquals(NextReason.NOT_STARTED, it.reason) }
        assertEquals("S02E01", next(eps.watch("S01E03", 100, 5)).episode?.label, "season 1 finale → season 2 premiere")
        var all = eps
        eps.forEachIndexed { i, e -> all = all.watch(e.label, 100, i + 1L) }
        next(all).let { assertEquals(NextAction.NONE, it.action); assertEquals(NextReason.SERIES_FINISHED, it.reason); assertNull(it.episode) }
        assertEquals(NextReason.NO_EPISODES, next(emptyList()).reason)
    }

    @Test
    fun stalePartialEpisodesDoNotSteer() {
        // E03 abandoned at 40 % long ago, then E04–E06 watched: next is E07, not E03.
        var eps = season(1, 8).watch("S01E01", 100, 1).watch("S01E02", 100, 2).watch("S01E03", 40, 3)
        eps = eps.watch("S01E04", 100, 10).watch("S01E05", 100, 11).watch("S01E06", 100, 12)
        assertEquals("S01E07", next(eps).episode?.label)
    }

    @Test
    fun goingBackDoesNotLoseThePlace() {
        // Watched E01–E05, then re-watched E02 → still E06 (episodes in between are done).
        var eps = season(1, 8)
        for (e in 1..5) eps = eps.watch("S01E%02d".format(e), 100, e.toLong())
        eps = eps.watch("S01E02", 100, 20)
        assertEquals("S01E06", next(eps).episode?.label)
        // E06 half watched earlier, then the user re-watched E05: E06 resumes where it was.
        eps = eps.watch("S01E06", 50, 21).watch("S01E05", 100, 22)
        next(eps).let { assertEquals("S01E06", it.episode?.label); assertEquals(NextAction.RESUME, it.action) }
    }

    @Test
    fun rewatchOfAFinishedSeries() {
        var eps = season(1, 4)
        eps.forEachIndexed { i, e -> eps = eps.watch(e.label, 100, i + 1L) }
        // Re-watching from the start: E01 finished a second time → E02.
        eps = eps.watch("S01E01", 100, 100)
        next(eps).let { assertEquals("S01E02", it.episode?.label); assertEquals(NextReason.REWATCH, it.reason) }
        eps = eps.watch("S01E02", 100, 101)
        assertEquals("S01E03", next(eps).episode?.label)
        // A re-watch in progress is resumed like any other.
        eps = eps.watch("S01E03", 20, 102)
        assertEquals(NextAction.RESUME, next(eps).action)
    }

    @Test
    fun newEpisodeAfterCatchingUp() {
        var eps = season(1, 3)
        eps.forEachIndexed { i, e -> eps = eps.watch(e.label, 100, i + 1L) }
        assertEquals(NextReason.SERIES_FINISHED, next(eps).reason)
        eps = eps + EpisodeProgress(99, 1, 4, addedAt = 500)
        next(eps).let { assertEquals("S01E04", it.episode?.label); assertEquals(NextReason.NEW_EPISODE, it.reason) }
    }

    @Test
    fun missingFilesAreSkippedAndReported() {
        var eps = season(1, 6).watch("S01E01", 100, 1).watch("S01E02", 100, 2)
        eps = eps.set("S01E03") { it.copy(available = false) }.set("S01E04") { it.copy(available = false) }
        next(eps).let {
            assertEquals("S01E05", it.episode?.label)
            assertEquals(listOf("S01E03", "S01E04"), it.skippedUnavailable.map { e -> e.label })
        }
        // The in-progress episode's file is gone: move on instead of offering something unplayable.
        val gone = season(1, 3).watch("S01E01", 50, 1).set("S01E01") { it.copy(available = false) }
        assertEquals("S01E02", next(gone).episode?.label)
    }

    @Test
    fun earlierGapAndOutOfOrderViewing() {
        // Watched the finale first, then E01 (once): the next unwatched is E02.
        var eps = season(1, 4).watch("S01E04", 100, 1).watch("S01E03", 100, 2).watch("S01E01", 100, 3)
        next(eps).let { assertEquals("S01E02", it.episode?.label); assertEquals(NextReason.NEXT_AFTER_COMPLETED, it.reason) }
        // Finished everything after the anchor but skipped E02 earlier: offer the gap.
        eps = season(1, 4).watch("S01E01", 100, 1).watch("S01E03", 100, 2).watch("S01E04", 100, 3)
        next(eps).let { assertEquals("S01E02", it.episode?.label); assertEquals(NextReason.EARLIER_GAP, it.reason) }
    }

    @Test
    fun specialsAndAbsoluteNumbering() {
        val specials = listOf(EpisodeProgress(500, 0, 1))
        var eps = season(1, 3) + specials
        eps = eps.watch("S01E01", 100, 1)
        assertEquals("S01E02", next(eps).episode?.label, "specials are not 'next'")
        eps = eps.watch("Special E01", 100, 5)
        assertEquals("S01E02", next(eps).episode?.label, "a finished special does not move the pointer")
        eps = eps.watch("Special E01", 30, 6)
        assertEquals("Special E01", next(eps).episode?.label, "but one in progress is resumed")
        // Anime-style absolute numbering.
        val abs = (1..3).map { EpisodeProgress(1000L + it, -1, it) }
        assertEquals("Episode 2", next(abs.watch("Episode 1", 100, 1)).episode?.label)
        // Bulk "mark watched" (same time): the later episode is the anchor.
        var bulk = season(1, 5)
        for (e in 1..3) bulk = bulk.watch("S01E%02d".format(e), 100, 42)
        assertEquals("S01E04", next(bulk).episode?.label)
    }

    @Test
    fun labelsAndPercent() {
        assertEquals("S01E05-E06", EpisodeProgress(1, 1, 5, numberEnd = 6).label)
        assertEquals(35, EpisodeProgress(1, 1, 1, positionMs = 35, durationMs = 100).percent)
        assertEquals(100, EpisodeProgress(1, 1, 1, completed = true).percent)
        assertEquals(99, Progress.percent(999, 1000, false), "unfinished is never 100 %")
        assertTrue(EpisodeProgress(1, 1, 1, positionMs = 1, lastWatchedAt = 1).inProgress)
    }
}
