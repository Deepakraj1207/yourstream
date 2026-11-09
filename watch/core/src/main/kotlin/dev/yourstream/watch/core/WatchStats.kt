package dev.yourstream.watch.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One viewing sitting from the local watch history, with what the library knows about the title. */
data class StatsSession(
    val itemId: Long,
    /** 0 for movies/videos, the episode id for series. */
    val episodeKey: Long,
    /** "MOVIE", "SERIES", "VIDEO" (null if the title is gone from the search index). */
    val kind: String?,
    /** ISO 639-1 audio languages of the title. */
    val languages: List<String>,
    val genres: List<String>,
    val startedAt: Long,
    val watchedAt: Long,
    val durationMs: Long,
    val completed: Boolean,
)

data class DayTotal(val day: LocalDate, val watchedMs: Long)

/** A language or genre and how much of the viewing time it had. */
data class Share(val key: String, val watchedMs: Long, val fraction: Float)

/** Viewing statistics from the device's own watch history. Nothing leaves the device. */
data class WatchStats(
    val totalMs: Long,
    val last7DaysMs: Long,
    /** The 7 days before those (for the trend). */
    val previous7DaysMs: Long,
    /** Oldest first, one entry per day, today last. */
    val daily: List<DayTotal>,
    val moviesFinished: Int,
    val episodesFinished: Int,
    /** Different titles watched at all. */
    val titlesWatched: Int,
    val sessions: Int,
    val averageSessionMs: Long,
    /** Consecutive days with viewing, ending today (or yesterday, if nothing yet today). */
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val topLanguages: List<Share>,
    val topGenres: List<Share>,
    /** Local hour (0–23) with the most viewing, null without history. */
    val busiestHour: Int?,
) {
    val isEmpty: Boolean get() = sessions == 0
}

/**
 * Pure statistics over watch sessions.
 *
 * Viewing time of a sitting is the wall time from its start to its last progress update, capped
 * at the file's duration (a sitting cannot be longer than what was played) and at 6 hours when the
 * duration is unknown. A sitting counts on the local day it started.
 */
object WatchStatsCalculator {
    private const val MAX_UNKNOWN_MS = 6 * 3_600_000L
    /** A day counts towards a streak with at least this much viewing. */
    private const val STREAK_MIN_MS = 60_000L

    fun watchedMs(s: StatsSession): Long {
        val wall = (s.watchedAt - s.startedAt).coerceAtLeast(0)
        return wall.coerceAtMost(if (s.durationMs > 0) s.durationMs else MAX_UNKNOWN_MS)
    }

    fun compute(sessions: List<StatsSession>, nowMs: Long, zone: ZoneId, days: Int = 14, top: Int = 3): WatchStats {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        val perDay = HashMap<LocalDate, Long>()
        val perHour = LongArray(24)
        val languages = HashMap<String, Long>()
        val genres = HashMap<String, Long>()
        var total = 0L
        var last7 = 0L
        var prev7 = 0L
        for (s in sessions) {
            val ms = watchedMs(s)
            total += ms
            val day = dayOf(s.startedAt)
            perDay.merge(day, ms, Long::plus)
            perHour[Instant.ofEpochMilli(s.startedAt).atZone(zone).hour] += ms
            val age = today.toEpochDay() - day.toEpochDay()
            if (age in 0..6) last7 += ms else if (age in 7..13) prev7 += ms
            // A title in two languages shares its time between them (shares then add up to 100 %).
            s.languages.distinct().takeIf { it.isNotEmpty() }?.let { l -> l.forEach { languages.merge(it, ms / l.size, Long::plus) } }
            s.genres.distinct().takeIf { it.isNotEmpty() }?.let { g -> g.forEach { genres.merge(it, ms / g.size, Long::plus) } }
        }

        val finished = sessions.filter { it.completed }.distinctBy { it.itemId to it.episodeKey }
        val (episodes, movies) = finished.partition { it.kind == "SERIES" || it.episodeKey != 0L }

        val activeDays = perDay.filterValues { it >= STREAK_MIN_MS }.keys.map { it.toEpochDay() }.toSortedSet()
        var current = 0
        var cursor = if (today.toEpochDay() in activeDays) today.toEpochDay() else today.toEpochDay() - 1
        while (cursor in activeDays) { current++; cursor-- }
        var longest = 0
        var run = 0
        var previous: Long? = null
        for (d in activeDays) {
            run = if (previous != null && d == previous + 1) run + 1 else 1
            longest = maxOf(longest, run)
            previous = d
        }

        fun shares(m: Map<String, Long>): List<Share> {
            val sum = m.values.sum().takeIf { it > 0 } ?: return emptyList()
            return m.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(top).map { Share(it.key, it.value, it.value.toFloat() / sum) }
        }

        return WatchStats(
            totalMs = total,
            last7DaysMs = last7,
            previous7DaysMs = prev7,
            daily = (days - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { DayTotal(it, perDay[it] ?: 0) } },
            moviesFinished = movies.size,
            episodesFinished = episodes.size,
            titlesWatched = sessions.map { it.itemId }.distinct().size,
            sessions = sessions.size,
            averageSessionMs = if (sessions.isEmpty()) 0 else total / sessions.size,
            currentStreakDays = current,
            longestStreakDays = longest,
            topLanguages = shares(languages),
            topGenres = shares(genres),
            busiestHour = perHour.indices.maxByOrNull { perHour[it] }?.takeIf { perHour[it] > 0 },
        )
    }
}
