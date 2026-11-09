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
 * generous timeouts and connection retries for slow mobile networks, a 25 % memory cache and a
 * 300 MB disk cache so scrolling a big library does not download posters twice.
 */
fun appImageLoader(context: Context, thumbnails: ThumbnailSource? = null): ImageLoader {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true).followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8").build())
        }
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
 * Poster links as add-ons and metadata give them, made loadable: `http://` is upgraded to
 * `https://` (Android blocks plain HTTP), protocol-relative `//host/…` gets `https:`, blanks are null.
 */
fun posterUrl(url: String?): String? {
    val u = url?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "N/A" } ?: return null
    return when {
        u.startsWith(TelegramThumbs.PREFIX) -> u
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

    /** Image model for the thumbnail of a message; "chatId:messageId" from the library ([ref]). */
    fun url(ref: String): String = PREFIX + ref.replace(':', '/')

    internal fun parse(url: String): Pair<Long, Long>? {
        val parts = url.removePrefix(PREFIX).split('/')
        return parts.getOrNull(0)?.toLongOrNull()?.let { c -> parts.getOrNull(1)?.toLongOrNull()?.let { m -> c to m } }
    }
}

private class TelegramThumbFetcher(private val url: String, private val source: ThumbnailSource) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val (chat, message) = TelegramThumbs.parse(url) ?: throw java.io.IOException("bad thumbnail reference")
        val path = source.localPath(chat, message) ?: throw java.io.IOException("no thumbnail")
        return SourceFetchResult(ImageSource(path.toPath(), FileSystem.SYSTEM), mimeType = null, dataSource = DataSource.DISK)
    }

    class Factory(private val source: ThumbnailSource) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val s = data.toString()
            return if (s.startsWith(TelegramThumbs.PREFIX)) TelegramThumbFetcher(s, source) else null
        }
    }
}
