package dev.yourstream.ui.core

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.util.concurrent.TimeUnit

/**
 * The app's poster loader: a browser-like User-Agent (some image hosts refuse unknown clients),
 * timeouts and connection retries for slow mobile networks, [ImageRouting] for hosts the
 * network blocks, a 25 % memory cache and a 300 MB disk cache so scrolling a big library does
 * not download posters twice.
 */
fun appImageLoader(context: Context, thumbnails: ThumbnailSource? = null): ImageLoader {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true).followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8").build())
        }
        .addInterceptor(ImageRouting())
        // Inside the routing: a blocked DNS answer is retried with secure DNS before any proxy.
        .addInterceptor(dev.yourstream.core.network.SecureDns.Retry())
        .dns(dev.yourstream.core.network.SecureDns.dns)
        .build()
    return ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            thumbnails?.let { add(TelegramThumbFetcher.Factory(it)) }
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("posters").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
        .crossfade(150)
        .build()
}

/**
 * Image hosts this network cannot reach are fetched through public image proxies instead.
 *
 * Some Indian ISPs (Jio above all) block TMDB, whose image server holds most posters; a poster
 * from there would never load. A request that fails (no connection, a block page instead of an
 * image, 403/451/5xx) is repeated through wsrv.nl, then WordPress's i0.wp.com, which fetch the
 * image on their side. The failing host is remembered for 30 minutes, so the next posters go
 * straight to a proxy instead of waiting for the block again. 404s are real: never proxied.
 */
class ImageRouting(
    private val now: () -> Long = System::currentTimeMillis,
    private val routes: (okhttp3.HttpUrl) -> List<String> = ::proxies,
) : okhttp3.Interceptor {
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val host = request.url.host
        if ((blockedUntil[host] ?: 0L) <= now()) {
            try {
                val direct = chain.proceed(request)
                if (direct.isSuccessful && direct.isImage()) {
                    blockedUntil.remove(host)
                    return direct
                }
                if (direct.code == 404 || direct.code == 410) return direct
                direct.close()
                blockedUntil[host] = now() + BLOCK_MS
            } catch (e: java.io.IOException) {
                if (chain.call().isCanceled()) throw e
                blockedUntil[host] = now() + BLOCK_MS
            }
        }
        var last: java.io.IOException? = null
        for (route in routes(request.url)) {
            try {
                val proxied = chain.proceed(request.newBuilder().url(route).build())
                if (proxied.isSuccessful && proxied.isImage()) return proxied
                proxied.close()
            } catch (e: java.io.IOException) {
                if (chain.call().isCanceled()) throw e
                last = e
            }
        }
        throw last ?: java.io.IOException("image not reachable: $host")
    }

    private fun okhttp3.Response.isImage(): Boolean {
        val type = header("Content-Type")?.lowercase() ?: return true
        return type.startsWith("image/") || type.startsWith("application/octet-stream") || type.startsWith("binary/")
    }

    companion object {
        private const val BLOCK_MS = 30 * 60_000L

        /** The same image through public image proxies, in order (pure; tested). */
        fun proxies(url: okhttp3.HttpUrl): List<String> {
            val bare = url.toString().substringAfter("://")
            return listOf(
                "https://wsrv.nl/?url=" + java.net.URLEncoder.encode(url.toString(), "UTF-8"),
                "https://i0.wp.com/" + bare.substringBefore('?') + (if (url.isHttps) "?ssl=1" else ""),
            )
        }
    }
}

/**
 * Artwork models in the order to try them. A title's artwork is one or more models separated by
 * new lines (its poster, then its Telegram thumbnail); in portrait (poster) slots, Telegram
 * thumbnails are only used when the thumbnail itself is portrait, never a channel's square logo
 * or a landscape video frame (pure; tested).
 */
fun artworkSources(artwork: String?, portraitSlot: Boolean): List<String> =
    artwork.orEmpty().split('\n').mapNotNull(::posterUrl)
        .map { if (portraitSlot && it.startsWith(TelegramThumbs.PREFIX)) TelegramThumbs.POSTER_PREFIX + it.removePrefix(TelegramThumbs.PREFIX) else it }
        .distinct()

/**
 * Poster links as add-ons and metadata give them, made loadable: `http://` is upgraded to
 * `https://` (Android blocks plain HTTP), protocol-relative `//host/…` gets `https:`, blanks are null.
 */
fun posterUrl(url: String?): String? {
    val u = url?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "N/A" } ?: return null
    return when {
        u.startsWith(TelegramThumbs.PREFIX) || u.startsWith(TelegramThumbs.POSTER_PREFIX) -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://", ignoreCase = true) -> "https://" + u.substring(7)
        u.startsWith("https://", ignoreCase = true) -> u
        else -> null
    }
}

private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/**
 * A video's own Telegram thumbnail, as artwork for titles without a matched poster. Movie
 * channels usually upload the poster as the thumbnail, so this is often exactly the right image.
 */
fun interface ThumbnailSource {
    /** Local file of the thumbnail of message [messageId] in [chatId] (downloaded if needed), or null. */
    suspend fun localPath(chatId: Long, messageId: Long): String?
}

object TelegramThumbs {
    const val PREFIX = "tgthumb://thumb/"
    /** The same thumbnail, but only when it is portrait (a poster); square logos and video frames are refused. */
    const val POSTER_PREFIX = "tgthumb://poster/"

    /** Image model for the thumbnail of a message; "chatId:messageId" from the library ([ref]). */
    fun url(ref: String): String = PREFIX + ref.replace(':', '/')

    internal fun parse(url: String): Pair<Long, Long>? {
        val parts = url.removePrefix(PREFIX).removePrefix(POSTER_PREFIX).split('/')
        return parts.getOrNull(0)?.toLongOrNull()?.let { c -> parts.getOrNull(1)?.toLongOrNull()?.let { m -> c to m } }
    }

    /** Portrait enough to be a poster: at least 1.15 times as tall as wide (pure; tested). */
    fun isPosterShaped(width: Int, height: Int): Boolean = width > 0 && height >= width * 1.15f
}

private class TelegramThumbFetcher(private val url: String, private val source: ThumbnailSource) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val (chat, message) = TelegramThumbs.parse(url) ?: throw java.io.IOException("bad thumbnail reference")
        val path = source.localPath(chat, message) ?: throw java.io.IOException("no thumbnail")
        if (url.startsWith(TelegramThumbs.POSTER_PREFIX)) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            if (!TelegramThumbs.isPosterShaped(bounds.outWidth, bounds.outHeight)) throw java.io.IOException("thumbnail is not a poster")
        }
        return SourceFetchResult(ImageSource(path.toPath(), FileSystem.SYSTEM), mimeType = null, dataSource = DataSource.DISK)
    }

    class Factory(private val source: ThumbnailSource) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val s = data.toString()
            return if (s.startsWith(TelegramThumbs.PREFIX) || s.startsWith(TelegramThumbs.POSTER_PREFIX)) TelegramThumbFetcher(s, source) else null
        }
    }
}
