package dev.yourstream.core.diagnostics

import java.io.File
import java.util.Properties

/** What was playing, saved every few seconds while it plays. */
data class PlaybackSession(
    val itemId: Long,
    val episodeId: Long?,
    val mediaId: Long?,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/**
 * Survives process death: the player saves the session while playing and clears it when the
 * user leaves the player normally. A session still present at the next start means Android
 * killed the process mid-playback; the app offers to resume it at the saved position.
 * Writes are atomic (temp file + rename), so a kill during a write leaves the previous session.
 */
class PlaybackSessionStore(private val file: File, private val maxAgeMs: Long = 24 * 3600_000L) {

    fun save(s: PlaybackSession) {
        runCatching {
            file.parentFile?.mkdirs()
            val p = Properties().apply {
                setProperty("itemId", s.itemId.toString())
                s.episodeId?.let { setProperty("episodeId", it.toString()) }
                s.mediaId?.let { setProperty("mediaId", it.toString()) }
                setProperty("title", s.title.take(200))
                setProperty("positionMs", s.positionMs.toString())
                setProperty("durationMs", s.durationMs.toString())
                setProperty("updatedAt", s.updatedAt.toString())
            }
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    /** The interrupted session, if recent enough to be worth resuming. */
    fun interrupted(now: Long): PlaybackSession? = read()?.takeIf { now - it.updatedAt in 0..maxAgeMs }

    fun read(): PlaybackSession? = runCatching {
        if (!file.exists()) return null
        val p = Properties().apply { file.inputStream().use { load(it) } }
        PlaybackSession(
            itemId = p.getProperty("itemId").toLong(),
            episodeId = p.getProperty("episodeId")?.toLongOrNull(),
            mediaId = p.getProperty("mediaId")?.toLongOrNull(),
            title = p.getProperty("title").orEmpty(),
            positionMs = p.getProperty("positionMs").toLong(),
            durationMs = p.getProperty("durationMs")?.toLongOrNull() ?: 0,
            updatedAt = p.getProperty("updatedAt").toLong(),
        )
    }.getOrNull() // a damaged file is simply no session

    fun clear() { runCatching { file.delete() } }
}
