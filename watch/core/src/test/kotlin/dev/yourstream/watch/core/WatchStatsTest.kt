package dev.yourstream.watch.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchStatsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 9)
    private val min = 60_000L
    private fun at(daysAgo: Int, hour: Int) = today.minusDays(daysAgo.toLong()).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at(0, 22)

    private fun s(item: Long, daysAgo: Int, minutes: Long, ep: Long = 0, kind: String = "MOVIE", langs: List<String> = listOf("ta"),
                  genres: List<String> = listOf("action"), completed: Boolean = false, duration: Long = 150 * min, hour: Int = 20) =
        StatsSession(item, ep, kind, langs, genres, at(daysAgo, hour), at(daysAgo, hour) + minutes * min, duration, completed)

    @Test
    fun totalsTrendStreaksAndShares() {
        val sessions = listOf(
            s(1, 0, 60, completed = true),                                                        // today: movie finished
            s(2, 1, 22, ep = 11, kind = "SERIES", langs = listOf("hi"), genres = listOf("animation"), completed = true, duration = 22 * min),
            s(2, 2, 22, ep = 12, kind = "SERIES", langs = listOf("hi"), genres = listOf("animation"), completed = true, duration = 22 * min),
            s(3, 9, 30, langs = listOf("ta", "te")),                                              // previous week, two languages
            s(4, 20, 40, hour = 9),                                                               // older: outside both weeks
            s(4, 21, 40, hour = 9),
        )
        val st = WatchStatsCalculator.compute(sessions, now, zone)
        assertEquals((60 + 22 + 22 + 30 + 40 + 40) * min, st.totalMs)
        assertEquals((60 + 22 + 22) * min, st.last7DaysMs)
        assertEquals(30 * min, st.previous7DaysMs)
        assertEquals(1, st.moviesFinished)
        assertEquals(2, st.episodesFinished)
        assertEquals(4, st.titlesWatched)
        assertEquals(3, st.currentStreakDays, "today, yesterday, the day before")
        assertEquals(3, st.longestStreakDays)
        // 14 days, today last.
        assertEquals(14, st.daily.size)
        assertEquals(today, st.daily.last().day)
        assertEquals(60 * min, st.daily.last().watchedMs)
        // Tamil: 60 + 40 + 40 + half of 30 = 155 of 254 minutes.
        assertEquals("ta", st.topLanguages.first().key)
        assertEquals(155 * min, st.topLanguages.first().watchedMs)
        assertTrue(st.topLanguages.sumOf { it.fraction.toDouble() } <= 1.0001)
        assertEquals(20, st.busiestHour)
    }

    @Test
    fun sittingsAreCappedAndStreaksSurviveAnUnwatchedToday() {
        // A sitting left open overnight counts at most the file's length.
        val open = s(1, 1, 600, duration = 100 * min)
        assertEquals(100 * min, WatchStatsCalculator.watchedMs(open))
        // Unknown duration: at most 6 hours.
        assertEquals(360 * min, WatchStatsCalculator.watchedMs(s(1, 1, 900, duration = 0)))
        // Nothing yet today: yesterday's run still counts; a 30 s glance does not extend it.
        val st = WatchStatsCalculator.compute(listOf(s(1, 1, 30), s(1, 2, 30), s(1, 3, 0)), now, zone)
        assertEquals(2, st.currentStreakDays)
    }

    @Test
    fun emptyHistory() {
        val st = WatchStatsCalculator.compute(emptyList(), now, zone)
        assertTrue(st.isEmpty)
        assertEquals(0, st.currentStreakDays)
        assertNull(st.busiestHour)
        assertEquals(emptyList(), st.topGenres)
    }
}
