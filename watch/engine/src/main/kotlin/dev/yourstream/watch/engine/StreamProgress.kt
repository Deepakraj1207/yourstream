package dev.yourstream.watch.engine

import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.database.entity.StreamProgressEntity
import dev.yourstream.library.data.CompletionPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resume points for add-on streams (web and torrent), which are not library files: per catalog
 * title and episode ([keyOf]), so any stream of the same episode resumes where the last one
 * stopped. The library player's rules ([CompletionPolicy]): nothing under 30 s; near the end
 * counts as finished and the next play starts over.
 */
@Singleton
class StreamProgressStore @Inject constructor(private val db: YourStreamDatabase, private val clock: Clock) {
    private val dao get() = db.streamProgressDao()

    /** Where to start [key]: the saved position, or null to start from the beginning. */
    suspend fun resumeAt(key: String): Long? =
        dao.get(key)?.takeIf { !it.completed }?.positionMs?.takeIf { it >= CompletionPolicy.MIN_RESUME_MS }

    suspend fun save(key: String, title: String, subtitle: String?, positionMs: Long, durationMs: Long) {
        if (positionMs < 0) return
        dao.upsert(StreamProgressEntity(key, title, subtitle, positionMs, durationMs, CompletionPolicy.isComplete(positionMs, durationMs), clock.nowMillis()))
        dao.trim(KEEP)
    }

    /** "Start over": forget the position. */
    suspend fun clear(key: String) = dao.delete(key)

    companion object {
        private const val KEEP = 500

        /**
         * The catalog identity of what plays: the IMDb id when known (the same across add-ons),
         * else the add-on's own id; with season and episode for series (pure; tested).
         */
        fun keyOf(type: String, id: String, imdb: String?, season: Int?, episode: Int?): String {
            val base = "${type.lowercase()}:${imdb ?: id}"
            return if (season != null || episode != null) "%s:S%02dE%02d".format(base, season ?: 0, episode ?: 0) else base
        }
    }
}
