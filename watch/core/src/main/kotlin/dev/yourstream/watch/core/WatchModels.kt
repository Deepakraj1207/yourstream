package dev.yourstream.watch.core

/** One episode with its watch progress, as the policies see it. */
data class EpisodeProgress(
    val episodeId: Long,
    /** ≥ 1 regular season, 0 specials, −1 season-less ("absolute") numbering. */
    val season: Int,
    val number: Int,
    val numberEnd: Int? = null,
    val title: String? = null,
    /** Has at least one playable file. */
    val available: Boolean = true,
    /** When a file for it last arrived (detects new episodes after the user caught up). */
    val addedAt: Long? = null,
    /** Resume point (0 when finished or only peeked at). */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    /** Times finished. */
    val playCount: Int = 0,
    /** Null = never played. */
    val lastWatchedAt: Long? = null,
    val lastMediaId: Long? = null,
) {
    val started: Boolean get() = lastWatchedAt != null
    val isSpecial: Boolean get() = season == 0
    val inProgress: Boolean get() = !completed && positionMs > 0
    /** 0..100. A finished episode is 100 whatever its stored position. */
    val percent: Int get() = Progress.percent(positionMs, durationMs, completed)
    val label: String get() = Progress.label(season, number, numberEnd)
}

object Progress {
    fun percent(positionMs: Long, durationMs: Long, completed: Boolean): Int = when {
        completed -> 100
        durationMs <= 0 || positionMs <= 0 -> 0
        else -> ((positionMs * 100) / durationMs).toInt().coerceIn(0, 99)
    }

    fun label(season: Int, number: Int, numberEnd: Int? = null): String = buildString {
        when {
            season > 0 -> append("S%02d".format(season))
            season == 0 -> append("Special ")
        }
        append(if (season < 0) "Episode $number" else "E%02d".format(number))
        if (numberEnd != null && numberEnd > number) append(if (season < 0) "–$numberEnd" else "-E%02d".format(numberEnd))
    }

    /**
     * Watch order: regular seasons ascending, then season-less numbering, then specials last
     * (specials are never picked as "next" automatically).
     */
    val WATCH_ORDER: Comparator<EpisodeProgress> = compareBy<EpisodeProgress>(
        { when { it.season > 0 -> 0; it.season < 0 -> 1; else -> 2 } }, { it.season }, { it.number }, { it.episodeId },
    )
}

enum class NextAction {
    /** Continue from the saved position. */
    RESUME,
    /** Play from the beginning. */
    START,
    /** Nothing to play next. */
    NONE,
}

enum class NextReason {
    /** The most recently watched episode is unfinished. */
    IN_PROGRESS,
    /** The episode after the most recently finished one. */
    NEXT_AFTER_COMPLETED,
    /** Like NEXT_AFTER_COMPLETED, but it arrived after the user had caught up. */
    NEW_EPISODE,
    /** Nothing watched yet: the first episode. */
    NOT_STARTED,
    /** The user is re-watching a finished series: the episode after the re-watched one. */
    REWATCH,
    /** Everything after the last-watched episode is done, but an earlier one was skipped. */
    EARLIER_GAP,
    /** Every available episode is finished. */
    SERIES_FINISHED,
    NO_EPISODES,
}

data class NextEpisodeDecision(
    val action: NextAction,
    val reason: NextReason,
    /** The episode to play (null for NONE). */
    val episode: EpisodeProgress?,
    /** The episode the decision was based on (the most recently watched regular one). */
    val anchor: EpisodeProgress?,
    /** Episodes passed over because no file is available for them ("S01E07 missing"). */
    val skippedUnavailable: List<EpisodeProgress> = emptyList(),
)

enum class WatchStatus { UNWATCHED, IN_PROGRESS, COMPLETED, ABANDONED }

enum class AbandonReason {
    /** Removed from Continue Watching by the user (until they play it again). */
    DISMISSED,
    /** Started, unfinished, untouched for a long time. */
    INACTIVE,
    /** Stopped early (a few minutes / one episode) and not touched since. */
    DROPPED_EARLY,
}

/** Tunable thresholds. */
data class WatchPolicy(
    val abandonMovieAfterDays: Int = 30,
    val abandonSeriesAfterDays: Int = 60,
    /** Stopped before this share of a movie (or after ≤ 1 episode of a series)… */
    val earlyDropPercent: Int = 15,
    /** …and idle this long: dropped early. */
    val earlyDropAfterDays: Int = 14,
)
