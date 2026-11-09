package dev.yourstream.playback.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decoders for every player: the device's own first, then FFmpeg's audio decoders (AC-3,
 * E-AC-3, DTS, TrueHD, …) for tracks the device cannot decode, so films with those soundtracks
 * play with sound everywhere. A decoder that fails to start falls back to the next one.
 */
@OptIn(UnstableApi::class)
fun playerRenderers(context: Context): RenderersFactory = DefaultRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    .setEnableDecoderFallback(true)

/**
 * Playback of web streams (what stream add-ons link to: files, HLS, DASH):
 *
 * - One OkHttp client for all of them: HTTP/2, connection reuse across HLS/DASH segments,
 *   redirects (http → https too, as debrid links do) followed.
 * - A 512 MB disk cache of what was played: seeking back and replays do not download again.
 * - Starts after 1.5 s of buffered media (3 s after a stall) and keeps up to two minutes ahead,
 *   within the default memory bounds.
 */
@OptIn(UnstableApi::class)
@Singleton
class WebStreams @Inject constructor(@ApplicationContext private val context: Context) {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val cache: SimpleCache by lazy {
        SimpleCache(File(context.cacheDir, "stream-cache"), LeastRecentlyUsedCacheEvictor(CACHE_BYTES), StandaloneDatabaseProvider(context))
    }

    /** Reads a stream with the request headers its add-on asked for (User-Agent, Referer, …). */
    fun dataSourceFactory(headers: Map<String, String>): DataSource.Factory {
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: USER_AGENT
        val http = OkHttpDataSource.Factory(client)
            .setUserAgent(agent)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 120_000, 1_500, 3_000)
        .build()

    fun player(mediaSourceFactory: MediaSource.Factory, loadControl: LoadControl = loadControl()): ExoPlayer =
        ExoPlayer.Builder(context, playerRenderers(context))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()

    private companion object {
        const val CACHE_BYTES = 512L shl 20
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) YourStream"
    }
}
