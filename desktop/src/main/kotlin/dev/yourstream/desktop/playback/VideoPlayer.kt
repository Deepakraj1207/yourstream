package dev.yourstream.desktop.playback

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Pointer
import dev.yourstream.core.common.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * VLC for the whole app: one libvlc instance, created once in the background. Creating it costs
 * one to two seconds (it loads the plugins), which used to happen on every Play, on the UI thread.
 */
class VlcEngine(private val logger: Logger) {
    sealed interface Status {
        data object Loading : Status
        data class Ready(val factory: MediaPlayerFactory) : Status
        data object Missing : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Loading)
    val status: StateFlow<Status> = _status.asStateFlow()
    private var started = false

    /** Finds VLC and starts libvlc off the UI thread (safe to call again; "retry" looks again). */
    suspend fun start(retry: Boolean = false): Status = withContext(Dispatchers.IO) {
        synchronized(this@VlcEngine) {
            val current = _status.value
            if (current is Status.Ready || (started && !retry && current != Status.Loading)) return@synchronized current
            started = true
            val found = if (retry) Vlc.retry(logger) else Vlc.available(logger)
            val next = if (!found) Status.Missing else try {
                Status.Ready(MediaPlayerFactory(*ARGS))
            } catch (t: Throwable) {
                logger.e("Vlc", "starting libvlc failed", t)
                Status.Missing
            }
            _status.value = next
            next
        }
    }

    fun close() {
        (_status.value as? Status.Ready)?.factory?.release()
    }

    companion object {
        val ARGS = arrayOf("--no-video-title-show", "--no-osd", "--no-snapshot-preview", "--no-stats", "--quiet")
    }
}

/** An audio or subtitle track as the menus show it. */
data class Track(val id: Int, val label: String, val language: String?)

/** Everything the player screen shows, in one value. */
data class PlayerState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val timeMs: Long = 0,
    val lengthMs: Long = 0,
    val finished: Boolean = false,
    val error: Boolean = false,
    val audio: List<Track> = emptyList(),
    val audioSelected: Int = -1,
    val subtitles: List<Track> = emptyList(),
    val subtitleSelected: Int = -1,
    val rate: Float = 1f,
    val volume: Int = 100,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/**
 * One playback: a VLC media player that draws into [frames] (so the controls can sit over the
 * picture), with its state as a flow.
 *
 * Every libvlc call runs on this player's own thread, never on the UI thread: a VLC call can
 * wait (stopping waits for VLC's network read to end, and that read may be waiting for Telegram),
 * and on the UI thread that froze the window. [close] returns at once.
 */
class VideoPlayerController(factory: MediaPlayerFactory, private val logger: Logger) : AutoCloseable {
    private val vlc: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-player").apply { isDaemon = true } }
    val frames = VideoFrames()
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    @Volatile private var closed = false

    init {
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))
        frames.onSize = { w, h -> _state.update { it.copy(videoWidth = w, videoHeight = h) } }
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) = _state.update { it.copy(buffering = true, error = false, finished = false) }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = _state.update { it.copy(buffering = newCache < 100f) }
            override fun playing(mediaPlayer: MediaPlayer) { _state.update { it.copy(playing = true, buffering = false) }; refreshTracksLater(); readVideoSizeLater() }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) = readVideoSizeLater()
            override fun paused(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false) }
            override fun stopped(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false) }
            override fun finished(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false, finished = true) }
            override fun error(mediaPlayer: MediaPlayer) = _state.update { it.copy(playing = false, buffering = false, error = true) }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = _state.update { it.copy(lengthMs = newLength) }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                // VLC reports often; a quarter second is plenty for the screen.
                if (kotlin.math.abs(newTime - _state.value.timeMs) >= 250) _state.update { it.copy(timeMs = newTime, buffering = false) }
            }
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) = refreshTracksLater()
            override fun elementaryStreamDeleted(mediaPlayer: MediaPlayer, type: TrackType, id: Int) = refreshTracksLater()
            override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: TrackType, id: Int) = refreshTracksLater()
        })
    }

    /**
     * Plays [url] from [startMs]. [audioLanguages] and [subtitleLanguages] are preferred language
     * codes, best first; VLC picks the matching track as the file opens. Subtitles are off when
     * [subtitleLanguages] is empty.
     */
    fun play(url: String, startMs: Long = 0, audioLanguages: List<String> = emptyList(), subtitleLanguages: List<String> = emptyList(), networkCachingMs: Int = NETWORK_CACHING_MS) = onVlc {
        _state.value = PlayerState(volume = _state.value.volume, rate = 1f)
        val options = buildList {
            // VLC waits for this much before it starts and after a seek; the stream server keeps
            // downloading far ahead, so a short cache is enough.
            add(":network-caching=$networkCachingMs")
            add(":http-reconnect")
            if (startMs > 5_000) add(":start-time=${startMs / 1000.0}")
            if (audioLanguages.isNotEmpty()) add(":audio-language=${audioLanguages.joinToString(",")}")
            add(if (subtitleLanguages.isNotEmpty()) ":sub-language=${subtitleLanguages.joinToString(",")}" else ":sub-track=-1")
        }
        player.media().play(url, *options.toTypedArray())
    }

    fun togglePause() = onVlc { if (player.status().isPlaying) player.controls().setPause(true) else player.controls().play() }

    fun seekTo(ms: Long) = onVlc {
        player.controls().setTime(ms.coerceAtLeast(0))
        _state.update { it.copy(timeMs = ms.coerceAtLeast(0), buffering = true) }
    }

    fun skip(deltaMs: Long) = seekTo(_state.value.timeMs + deltaMs)

    fun setRate(rate: Float) = onVlc { if (player.controls().setRate(rate)) _state.update { it.copy(rate = rate) } }

    fun setVolume(volume: Int) = onVlc {
        val v = volume.coerceIn(0, 150)
        player.audio().setVolume(v)
        _state.update { it.copy(volume = v) }
    }

    fun selectAudio(id: Int) = onVlc { player.audio().setTrack(id); refreshTracks() }

    fun selectSubtitle(id: Int) = onVlc { player.subpictures().setTrack(id); refreshTracks() }

    private fun refreshTracksLater() = onVlc { refreshTracks() }

    private fun readVideoSizeLater() = onVlc {
        val d = player.video().videoDimension() ?: return@onVlc
        frames.setVisible(d.width, d.height)
    }

    private fun refreshTracks() {
        val info = player.media().info()
        val audioInfo = info?.audioTracks().orEmpty().associateBy { it.id() }
        val textInfo = info?.textTracks().orEmpty().associateBy { it.id() }
        val audio = player.audio().trackDescriptions().filter { it.id() >= 0 }.mapIndexed { i, d ->
            val t = audioInfo[d.id()]
            Track(d.id(), TrackLabels.audio(i + 1, d.description(), t?.language(), t?.description(), t?.codecName(), t?.channels()), TrackLabels.code(t?.language()))
        }
        val subtitles = player.subpictures().trackDescriptions().filter { it.id() >= 0 }.mapIndexed { i, d ->
            val t = textInfo[d.id()]
            Track(d.id(), TrackLabels.subtitle(i + 1, d.description(), t?.language(), t?.description()), TrackLabels.code(t?.language()))
        }
        _state.update {
            it.copy(audio = audio, audioSelected = player.audio().track(), subtitles = subtitles, subtitleSelected = player.subpictures().track())
        }
    }

    private fun onVlc(block: () -> Unit) {
        if (closed) return
        try {
            vlc.execute {
                try { block() } catch (t: Throwable) { logger.w("Player", "VLC call failed: ${t::class.simpleName}", t) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    /** Current position and length, read on the VLC thread (for saving where playback stopped). */
    suspend fun position(): Pair<Long, Long> {
        val result = CompletableDeferred<Pair<Long, Long>>()
        onVlc { result.complete(player.status().time() to player.status().length()) }
        return withContext(Dispatchers.IO) { runCatching { kotlinx.coroutines.withTimeout(2_000) { result.await() } }.getOrDefault(_state.value.timeMs to _state.value.lengthMs) }
    }

    /** Stops and frees the player in the background; never waits. */
    override fun close() {
        if (closed) return
        closed = true
        vlc.execute {
            try {
                player.controls().stop()
                player.release()
            } catch (t: Throwable) {
                logger.w("Player", "releasing VLC failed", t)
            }
        }
        vlc.shutdown()
    }

    companion object {
        /**
         * How much VLC buffers before it starts and after a seek. StreamingBenchmark: 1500 ms →
         * 600 ms makes seeks about 1 s faster; less gains nothing (Telegram's round trip dominates).
         */
        const val NETWORK_CACHING_MS = 600
    }

    /** For tests: waits until queued VLC calls are done. */
    internal fun awaitIdle(timeoutMs: Long = 5_000) {
        val done = java.util.concurrent.CountDownLatch(1)
        onVlc { done.countDown() }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

/**
 * VLC's frames into the app's own drawing. VLC decodes into its buffer; each frame is copied once,
 * straight from that native buffer into the native pixels of one of three Skia bitmaps, which the
 * screen draws scaled on the GPU. Three buffers: the newest frame, the one the screen is drawing
 * (never written to), and the one being filled. No allocation per frame.
 */
class VideoFrames : BufferFormatCallback, RenderCallback {
    private val lock = Any()
    private var bitmaps: Array<Bitmap> = emptyArray()
    private var images: Array<ImageBitmap> = emptyArray()
    private var width = 0
    private var height = 0
    private var latest = -1
    private var drawing = -1

    /** Bumped for every new frame (the screen redraws when it changes). */
    val frameId = mutableLongStateOf(0L)

    /** Video size, when VLC knows it. */
    @Volatile var onSize: (Int, Int) -> Unit = { _, _ -> }

    /**
     * The picture's own size. Decoders work in 16-pixel blocks, so VLC's buffer can be taller or
     * wider than the picture (a 180-line video arrives in a 194-line buffer); only this part is
     * drawn, or the padding would show as a stripe.
     */
    @Volatile var visibleWidth = 0
        private set
    @Volatile var visibleHeight = 0
        private set

    /** The picture's own size (VLC's buffer sizes include the padding, so this comes from the player). */
    fun setVisible(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == visibleWidth && h == visibleHeight)) return
        visibleWidth = minOf(w, width.takeIf { it > 0 } ?: w)
        visibleHeight = minOf(h, height.takeIf { it > 0 } ?: h)
        onSize(visibleWidth, visibleHeight)
    }

    /** The newest frame, marked as being drawn until the next call. Null before the first. */
    fun acquire(): ImageBitmap? = synchronized(lock) {
        if (latest < 0) return null
        drawing = latest
        images[latest]
    }

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        synchronized(lock) {
            width = sourceWidth
            height = sourceHeight
            val info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            bitmaps = Array(3) { Bitmap().apply { allocPixels(info) } }
            images = Array(3) { bitmaps[it].asComposeImageBitmap() }
            latest = -1
            drawing = -1
            if (visibleWidth > sourceWidth || visibleWidth == 0) visibleWidth = sourceWidth
            if (visibleHeight > sourceHeight || visibleHeight == 0) visibleHeight = sourceHeight
        }
        return RV32BufferFormat(sourceWidth, sourceHeight)
    }

    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    override fun lock(mediaPlayer: MediaPlayer) = Unit
    override fun unlock(mediaPlayer: MediaPlayer) = Unit

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {

        val target: Int
        val bitmap: Bitmap
        synchronized(lock) {
            if (bitmaps.isEmpty()) return
            target = (0 until 3).first { it != latest && it != drawing }
            bitmap = bitmaps[target]
        }
        val bytes = width * 4 * height
        val pixels = bitmap.peekPixels() ?: return
        val dst = Pointer(pixels.addr).getByteBuffer(0, bytes.toLong())
        val src = nativeBuffers[0].duplicate()
        src.position(0)
        src.limit(minOf(src.capacity(), bytes))
        dst.put(src)
        bitmap.notifyPixelsChanged()
        synchronized(lock) { latest = target }
        frameId.longValue = frameId.longValue + 1
    }
}

/** Menu names for tracks, from what the file says about them (pure; tested). */
object TrackLabels {
    private val iso3to1: Map<String, String> = java.util.Locale.getISOLanguages().associateBy { runCatching { java.util.Locale.forLanguageTag(it).isO3Language }.getOrDefault(it) } +
        mapOf("chi" to "zh", "fre" to "fr", "ger" to "de", "dut" to "nl", "gre" to "el", "per" to "fa", "cze" to "cs", "rum" to "ro", "slo" to "sk", "may" to "ms", "bur" to "my", "arm" to "hy", "geo" to "ka", "ice" to "is", "mac" to "mk", "alb" to "sq", "baq" to "eu", "wel" to "cy", "tib" to "bo")

    /** ISO 639-1 code ("ta") for a track's language ("tam", "ta", "Tamil"), or null. */
    fun code(language: String?): String? {
        val l = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" && it != "unknown" } ?: return null
        if (l.length == 2) return l
        if (l.length == 3) return iso3to1[l] ?: l
        return java.util.Locale.getISOLanguages().firstOrNull { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).equals(l, ignoreCase = true) }
    }

    fun languageName(language: String?): String? {
        val c = code(language) ?: return null
        val name = java.util.Locale.forLanguageTag(c).getDisplayLanguage(java.util.Locale.ENGLISH)
        return if (name.isBlank() || name.equals(c, ignoreCase = true)) c.uppercase() else name
    }

    fun codec(fourcc: String?): String? = when (fourcc?.trim()?.lowercase()) {
        null, "" -> null
        "a52", "ac-3", "ac3" -> "AC3"
        "eac3", "ec-3" -> "E-AC3"
        "mp4a", "aac" -> "AAC"
        "dts", "dtsh" -> "DTS"
        "mpga", "mp3" -> "MP3"
        "mlp", "trhd", "truehd" -> "TrueHD"
        "opus" -> "Opus"
        "vorb", "vorbis" -> "Vorbis"
        "flac" -> "FLAC"
        "araw", "s16l", "s24l", "lpcm" -> "PCM"
        else -> fourcc.trim().uppercase()
    }

    fun channels(n: Int?): String? = when (n) {
        null, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "$n ch"
    }

    /** "Tamil · DD+ Atmos · E-AC3 5.1": language, the file's own track name, format. */
    fun audio(index: Int, vlcDescription: String?, language: String?, name: String?, codec: String?, channels: Int?): String {
        val lang = languageName(language)
        val own = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(lang, ignoreCase = true) }
        val format = listOfNotNull(codec(codec), channels(channels)).joinToString(" ").ifEmpty { null }
        val parts = listOfNotNull(lang, own, format)
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return cleanVlc(vlcDescription) ?: "Track $index"
    }

    fun subtitle(index: Int, vlcDescription: String?, language: String?, name: String?): String {
        val lang = languageName(language)
        val own = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(lang, ignoreCase = true) }
        val parts = listOfNotNull(lang, own)
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return cleanVlc(vlcDescription) ?: "Subtitles $index"
    }

    /** VLC's own "Track 1 - [Tamil]" → "Tamil". */
    private fun cleanVlc(d: String?): String? {
        val s = d?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        Regex("""\[([^\]]+)]""").find(s)?.let { return it.groupValues[1] }
        return s
    }
}
