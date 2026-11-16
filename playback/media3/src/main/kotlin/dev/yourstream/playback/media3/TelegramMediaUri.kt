package dev.yourstream.playback.media3

import android.net.Uri
import dev.yourstream.playback.stream.StreamHints

/**
 * How a Telegram file is addressed inside Media3: `tgfile://file/<fileId>?size=…&durationMs=…`.
 * A file in raw pieces is `tgfile://split/<id1>/<id2>/…?sizes=<s1>,<s2>,…`: the pieces in order
 * with their exact sizes, read as one file. File ids are Telegram's (valid for the current session
 * only), so these URIs are built at play time by the resolver and never stored.
 */
data class TelegramMediaUri(
    val fileId: Int,
    val hints: StreamHints = StreamHints(),
    /** The pieces of a split file (file id, exact size), first = [fileId]; empty for one file. */
    val parts: List<Pair<Int, Long>> = emptyList(),
) {
    val isSplit: Boolean get() = parts.size > 1

    fun toUri(): Uri = if (isSplit) {
        Uri.Builder().scheme(SCHEME).authority(SPLIT).apply {
            parts.forEach { appendPath(it.first.toString()) }
            appendQueryParameter("sizes", parts.joinToString(",") { it.second.toString() })
            hints.durationMs?.let { appendQueryParameter("durationMs", it.toString()) }
        }.build()
    } else {
        Uri.Builder().scheme(SCHEME).authority("file").appendPath(fileId.toString()).apply {
            hints.sizeBytes?.let { appendQueryParameter("size", it.toString()) }
            hints.durationMs?.let { appendQueryParameter("durationMs", it.toString()) }
        }.build()
    }

    companion object {
        const val SCHEME = "tgfile"
        private const val SPLIT = "split"

        fun split(parts: List<Pair<Int, Long>>, durationMs: Long? = null): TelegramMediaUri =
            TelegramMediaUri(parts.first().first, StreamHints(parts.sumOf { it.second }, durationMs), parts)

        fun parse(uri: Uri): TelegramMediaUri? {
            if (uri.scheme != SCHEME) return null
            val duration = uri.getQueryParameter("durationMs")?.toLongOrNull()
            if (uri.authority == SPLIT) {
                val ids = uri.pathSegments.map { it.toIntOrNull() ?: return null }
                val sizes = uri.getQueryParameter("sizes")?.split(',')?.map { it.toLongOrNull() ?: return null } ?: return null
                if (ids.size < 2 || ids.size != sizes.size || sizes.any { it <= 0 }) return null
                return split(ids.zip(sizes), duration)
            }
            val id = uri.lastPathSegment?.toIntOrNull() ?: return null
            return TelegramMediaUri(id, StreamHints(uri.getQueryParameter("size")?.toLongOrNull(), duration))
        }
    }
}
