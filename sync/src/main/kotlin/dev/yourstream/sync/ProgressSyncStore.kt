package dev.yourstream.sync

import androidx.room.withTransaction
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.WatchStateEntity
import dev.yourstream.library.data.RoomWatchHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Moves watch progress between the local database and portable [ProgressRecord]s. */
@Singleton
class ProgressSyncStore @Inject constructor(
    private val db: YourStreamDatabase,
    private val history: RoomWatchHistoryRepository,
) {
    private val dao = db.progressSyncDao()
    private val watch = db.watchDao()

    data class LocalUnit(val itemId: Long, val episodeKey: Long, val mediaId: Long?)

    /** Every unit with progress on this device, as portable records. */
    suspend fun export(): List<ProgressRecord> = dao.exportStates(since = 0).mapNotNull { r ->
        // "video:<media id>" keys are local ids: only the file identifies such a video elsewhere.
        if (r.matchKey.startsWith("video:") && r.fileUniqueId == null) return@mapNotNull null
        ProgressRecord(
            file = r.fileUniqueId, key = r.matchKey, season = r.seasonNumber, episode = r.episodeNumber,
            positionMs = r.positionMs, durationMs = r.durationMs, completed = r.completed, playCount = r.playCount, at = r.lastWatchedAt,
        )
    }

    /** The local unit of a record: by file first, then by title key and episode numbers. */
    suspend fun resolve(r: ProgressRecord): LocalUnit? {
        r.file?.let { f -> dao.unitByFile(f)?.let { return LocalUnit(it.itemId, it.episodeKey, it.mediaId) } }
        if (r.key.startsWith("video:")) return null
        val item = dao.itemByKey(r.key) ?: return null
        if (r.season == null || r.episode == null) return if (r.key.startsWith("series:")) null else LocalUnit(item, 0, null)
        val episode = dao.episodeByNumber(item, r.season, r.episode) ?: return null
        return LocalUnit(item, episode, null)
    }

    /**
     * Applies records that are newer than this device's own state for the same unit. Records for
     * files this device does not have are skipped (and kept in the shared snapshot by the caller).
     * Watch history (the statistics) is not touched: it stays per device.
     *
     * @return how many units changed.
     */
    suspend fun apply(records: List<ProgressRecord>): Int {
        val resolved = records.mapNotNull { r -> resolve(r)?.let { it to r } }
        val newest = resolved.groupBy({ (u, _) -> u.itemId to u.episodeKey }) { it }
            .mapValues { (_, list) -> list.reduce { a, b -> if (ProgressMerge.newer(a.second, b.second) === a.second) a else b } }
        if (newest.isEmpty()) return 0
        return db.withTransaction {
            var changed = 0
            val items = HashSet<Long>()
            for ((_, pair) in newest) {
                val (unit, r) = pair
                val local = watch.state(unit.itemId, unit.episodeKey)
                if (!ProgressMerge.shouldApply(r, local?.lastWatchedAt)) continue
                watch.upsertState(
                    WatchStateEntity(
                        itemId = unit.itemId, episodeKey = unit.episodeKey,
                        positionMs = r.positionMs, durationMs = r.durationMs.takeIf { it > 0 } ?: local?.durationMs ?: 0,
                        completed = r.completed, completedAt = if (r.completed) local?.completedAt ?: r.at else null,
                        playCount = maxOf(r.playCount, local?.playCount ?: 0),
                        lastWatchedAt = r.at, lastMediaId = unit.mediaId ?: local?.lastMediaId,
                    ),
                )
                items += unit.itemId
                changed++
            }
            items.forEach { history.refreshAggregates(it) }
            changed
        }
    }
}
