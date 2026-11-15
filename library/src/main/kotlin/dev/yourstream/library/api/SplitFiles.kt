package dev.yourstream.library.api

import dev.yourstream.media.filename.FileExtensions
import java.util.Locale

/**
 * Movies cut into raw pieces to fit Telegram's upload limit: "Film.2160p.mkv.001", ".002", … made
 * by 7-Zip ("split to volumes"), HJSplit or `split`. Only the first piece starts with the
 * container's header; joined in order, the pieces are the original file byte for byte, so they
 * are played as one file (see the playback module's split data source).
 *
 * The library shows such a movie as one version (the first piece, with every piece listed in
 * [MediaVersion.parts]); later pieces are not versions of their own, because none can play alone.
 * Archive pieces ("Film.7z.001", "Film.part1.rar") are not joined: they would have to be unpacked.
 */
object SplitFiles {
    private val RAW_PIECE = Regex("^(.+\\.([A-Za-z0-9]{2,5}))\\.(\\d{3})$")

    /** "Film.mkv.002" → ("Film.mkv", 2); null for any other name, including archive pieces. */
    fun piece(fileName: String): Pair<String, Int>? {
        val m = RAW_PIECE.matchEntire(fileName.trim()) ?: return null
        if (m.groupValues[2].lowercase(Locale.ROOT) !in FileExtensions.VIDEO) return null
        val n = m.groupValues[3].toInt()
        return if (n >= 1) m.groupValues[1] to n else null
    }

    /**
     * Joins the pieces among [versions]: a first piece becomes one version holding every piece
     * ([siblings] finds them in its chat by name, wherever they were linked); later pieces are
     * dropped. When a piece was posted more than once, the copy nearest the first piece's message
     * is used. A first piece with no other piece found stays a normal version.
     */
    suspend fun join(versions: List<MediaVersion>, siblings: suspend (chatId: Long, base: String) -> List<SplitPart>): List<MediaVersion> =
        versions.mapNotNull { v ->
            if (v.isLocal) return@mapNotNull v
            val (base, number) = piece(v.fileName) ?: return@mapNotNull v
            if (number != 1) return@mapNotNull null
            val first = SplitPart(1, v.mediaId, v.messageId, v.fileName, v.sizeBytes, v.isDeleted, v.downloadState)
            val others = siblings(v.chatId, base).filter { it.number > 1 }
                .groupBy { it.number }
                .map { (_, copies) -> copies.minBy { kotlin.math.abs(it.messageId - v.messageId) } }
            if (others.isEmpty()) return@mapNotNull v
            val parts = (listOf(first) + others).sortedBy { it.number }
            v.copy(parts = parts, sizeBytes = parts.sumOf { it.sizeBytes }, downloadState = downloadState(parts))
        }

    /** On this device only when every piece is; partly when some piece has bytes on the device. */
    fun downloadState(parts: List<SplitPart>): String = when {
        parts.all { it.downloadState == COMPLETE } -> COMPLETE
        parts.any { it.downloadState == COMPLETE || it.downloadState == PARTIAL } -> PARTIAL
        else -> NOT_DOWNLOADED
    }

    private const val COMPLETE = "COMPLETE"
    private const val PARTIAL = "PARTIAL"
    private const val NOT_DOWNLOADED = "NOT_DOWNLOADED"

    /**
     * Piece numbers that cannot be read: gaps in the numbering and deleted pieces. A missing last
     * piece cannot be noticed (nothing says how many there were).
     */
    fun missing(parts: List<SplitPart>): List<Int> {
        if (parts.isEmpty()) return emptyList()
        val present = parts.filter { !it.isDeleted }.map { it.number }.toSet()
        return (1..parts.maxOf { it.number }).filter { it !in present }
    }
}
