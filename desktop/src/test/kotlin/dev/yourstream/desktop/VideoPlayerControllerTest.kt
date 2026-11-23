package dev.yourstream.desktop

import androidx.compose.ui.graphics.toPixelMap
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.playback.ByteSource
import dev.yourstream.desktop.playback.PlayerState
import dev.yourstream.desktop.playback.StreamServer
import dev.yourstream.desktop.playback.VideoPlayerController
import dev.yourstream.desktop.playback.VlcEngine
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The new player with real VLC (headless: frames go to the app's own buffers, as on screen),
 * playing a real MKV with two audio languages and a subtitle track through the stream server and
 * the simulated Telegram download. Skipped where VLC is not installed.
 */
class VideoPlayerControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(90)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: VlcEngine
    private lateinit var sim: SimulatedTelegramFiles
    private lateinit var server: StreamServer
    private lateinit var clip: File
    private var player: VideoPlayerController? = null

    @Before fun setUp() {
        engine = VlcEngine(NoOpLogger)
        val status = runBlocking { engine.start() }
        assumeTrue("VLC not installed", status is VlcEngine.Status.Ready)
        // 10 s, 320x180 H.264; audio 1: Tamil AC3 5.1 named "DD 5.1"; audio 2: English AAC stereo;
        // English subtitles (not on by default). Made with ffmpeg; 0.25 MB.
        clip = File(tmp.root, "multi.mkv").also { it.writeBytes(VideoPlayerControllerTest::class.java.getResourceAsStream("/multi-audio.mkv")!!.readBytes()) }
        sim = SimulatedTelegramFiles(tmp.newFolder("td")).apply { bandwidthBytesPerSecond = 512L * 1024; latencyMs = 10; addFile(9, clip) }
        val streamer = TelegramFileStreamer(sim, scope)
        server = StreamServer(object : ByteSource {
            override suspend fun size(fileId: Int) = clip.length()
            override suspend fun open(fileId: Int, position: Long): ByteSource.Reader {
                val s = streamer.open(fileId, position)
                return object : ByteSource.Reader {
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = s.read(buffer, offset, length)
                    override fun close() = s.close()
                }
            }
        }, NoOpLogger)
    }

    @After fun tearDown() {
        player?.close()
        if (::server.isInitialized) server.close()
        if (::sim.isInitialized) sim.close()
        scope.cancel()
        if (::engine.isInitialized) engine.close()
    }

    private fun start(audio: List<String> = emptyList()): VideoPlayerController {
        val factory = (engine.status.value as VlcEngine.Status.Ready).factory
        return VideoPlayerController(factory, NoOpLogger).also { player = it; it.play(server.urlFor(9, "multi.mkv"), 0, audio) }
    }

    private fun VideoPlayerController.waitFor(timeoutMs: Long = 20_000, what: String, condition: (PlayerState) -> Boolean): PlayerState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition(state.value)) return state.value
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what; state: ${state.value}")
    }

    @Test
    fun playsAndDrawsFramesIntoTheAppsOwnBuffers() {
        val p = start()
        p.waitFor(what = "playing past 1 s") { it.playing && it.timeMs > 1_000 }
        assertTrue(p.frames.frameId.longValue > 5, "frames arrived: ${p.frames.frameId.longValue}")
        val frame = p.frames.acquire()!!
        // The buffer is block-aligned (taller than the picture); the visible part is the video.
        assertEquals(320 to 180, p.frames.visibleWidth to p.frames.visibleHeight)
        assertTrue(frame.height >= 180, "buffer ${frame.width}x${frame.height}")
        // testsrc2 is colourful: the picture is not empty.
        val pixels = frame.toPixelMap()
        val distinct = (0 until 10).flatMap { y -> (0 until 10).map { x -> pixels[x * 32, y * 18] } }.toSet()
        assertTrue(distinct.size > 3, "a real picture, not a blank frame: $distinct")
        assertEquals(320 to 180, p.state.value.videoWidth to p.state.value.videoHeight)
        assertTrue(p.state.value.lengthMs in 9_000..11_000, "length ${p.state.value.lengthMs}")
    }

    @Test
    fun audioTracksAreNamedByLanguageAndFormat() {
        val p = start()
        // VLC reports the tracks and then which one it picked; wait for both.
        val s = p.waitFor(what = "two audio tracks, one playing") { it.audio.size == 2 && it.audioSelected >= 0 }
        val labels = s.audio.map { it.label }
        assertEquals(listOf("Tamil · DD 5.1 · AC3 5.1", "English · AAC Stereo"), labels)
        assertEquals(listOf("ta", "en"), s.audio.map { it.language })
        // The file's default track plays first; subtitles stay off unless asked for.
        assertEquals(s.audio[0].id, s.audioSelected)
        assertEquals(listOf("English"), s.subtitles.map { it.label })
        assertTrue(s.subtitleSelected < 0, "subtitles off")
        // Switching works and is reported.
        p.selectAudio(s.audio[1].id)
        p.waitFor(what = "English selected") { it.audioSelected == s.audio[1].id }
        p.selectSubtitle(s.subtitles[0].id)
        p.waitFor(what = "subtitles on") { it.subtitleSelected == s.subtitles[0].id }
    }

    @Test
    fun thePreferredAudioLanguageIsPickedAtStart() {
        val p = start(audio = listOf("en"))
        val s = p.waitFor(what = "tracks known") { it.audio.size == 2 && it.audioSelected >= 0 }
        assertEquals("en", s.audio.first { it.id == s.audioSelected }.language)
    }

    /** The freeze: closing while VLC waits for data that is not coming must not block the caller. */
    @Test
    fun closingNeverWaitsForVlc() {
        val p = start()
        p.waitFor(what = "playing") { it.playing }
        sim.online = false // Telegram stalls: VLC's read waits.
        p.seekTo(8_000)
        Thread.sleep(500)
        val t0 = System.nanoTime()
        server.abort(9)
        p.close()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 100, "close returned in $ms ms")
        player = null
    }
}
