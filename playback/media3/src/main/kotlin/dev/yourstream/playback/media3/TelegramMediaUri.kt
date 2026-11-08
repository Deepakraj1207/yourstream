package dev.yourstream.playback.media3

import android.net.Uri
import dev.yourstream.playback.stream.StreamHints

/**
 * How a Telegram file is addressed inside Media3: `tgfile://file/<fileId>?size=…&durationMs=…`.
 * The file id is Telegram's (valid for the current session only), so these URIs are built at
 * play time by the resolver and never stored.
 */
data class TelegramMediaUri(val fileId: Int, val hints: StreamHints = StreamHints()) {
    fun toUri(): Uri = Uri.Builder().scheme(SCHEME).authority("file").appendPath(fileId.toString()).apply {
        hints.sizeBytes?.let { appendQueryParameter("size", it.toString()) }
        hints.durationMs?.let { appendQueryParameter("durationMs", it.toString()) }
    }.build()

    companion object {
        const val SCHEME = "tgfile"

        fun parse(uri: Uri): TelegramMediaUri? {
            if (uri.scheme != SCHEME) return null
            val id = uri.lastPathSegment?.toIntOrNull() ?: return null
            return TelegramMediaUri(id, StreamHints(uri.getQueryParameter("size")?.toLongOrNull(), uri.getQueryParameter("durationMs")?.toLongOrNull()))
        }
    }
}
