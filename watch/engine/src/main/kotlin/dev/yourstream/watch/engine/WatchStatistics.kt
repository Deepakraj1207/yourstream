package dev.yourstream.watch.engine

import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.watch.core.StatsSession
import dev.yourstream.watch.core.WatchStats
import dev.yourstream.watch.core.WatchStatsCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Viewing statistics, computed on the device from its own watch history (live). */
interface WatchStatistics {
    fun stats(): Flow<WatchStats>
}

@Singleton
class RoomWatchStatistics @Inject constructor(
    db: YourStreamDatabase,
    private val clock: Clock,
) : WatchStatistics {
    private val dao = db.watchDao()
    var zone: () -> ZoneId = { ZoneId.systemDefault() }

    override fun stats(): Flow<WatchStats> = dao.observeSessions(since = 0).map { rows ->
        WatchStatsCalculator.compute(
            rows.map { r ->
                StatsSession(r.itemId, r.episodeKey, r.kind, split(r.languages), split(r.genres), r.startedAt, r.watchedAt, r.durationMs, r.completed)
            },
            clock.nowMillis(), zone(),
        )
    }

    private fun split(s: String) = s.split(',').filter { it.isNotBlank() }
}
