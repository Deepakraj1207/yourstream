package dev.yourstream.ui.core

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/**
 * The app's poster loader: a browser-like User-Agent (some image hosts refuse unknown clients),
 * generous timeouts and connection retries for slow mobile networks, a 25 % memory cache and a
 * 300 MB disk cache so scrolling a big library does not download posters twice.
 */
fun appImageLoader(context: Context): ImageLoader {
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
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
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
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://", ignoreCase = true) -> "https://" + u.substring(7)
        u.startsWith("https://", ignoreCase = true) -> u
        else -> null
    }
}

private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
