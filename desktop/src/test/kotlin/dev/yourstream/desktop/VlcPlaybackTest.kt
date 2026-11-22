package dev.yourstream.desktop

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.playback.ByteSource
import dev.yourstream.desktop.playback.StreamServer
import dev.yourstream.desktop.playback.Vlc
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Real VLC (libvlc through vlcj, as in the app) playing real H.264/AAC MKV files from the stream
 * server, which streams them from the simulated Telegram download at a limited speed.
 *
 * Needs VLC (skipped without it); the seek test also needs ffmpeg to make a clip big enough that
 * VLC seeks with a new byte range instead of reading on. The video window itself
 * (EmbeddedMediaPlayerComponent) needs a desktop session and is not part of these tests.
 */
class VlcPlaybackTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The committed clip: 30 s of 240p H.264/AAC in MKV (a test pattern made with ffmpeg; 0.5 MB). */
    @Test
    fun vlcPlaysAStreamedFile() {
        assumeTrue("VLC not installed", Vlc.available(NoOpLogger))
        val clip = File(tmp.root, "clip.mkv").also { it.writeBytes(VlcPlaybackTest::class.java.getResourceAsStream("/clip-30s.mkv")!!.readBytes()) }
        Harness(clip, tmp.newFolder("td"), bandwidth = 64L * 1024).use { h ->
            h.play()
            h.waitUntil(20_000) { h.player.status().time() > 3_000 }
            assertTrue(h.player.status().time() > 3_000, "playing: ${h.player.status().time()} ms")
            val length = h.player.status().length()
            assertTrue(length in 29_000..31_000, "length read from the stream: $length ms")
        }
    }

    /** A 9 MB clip: seeking ahead asks the server for a new byte range, read while downloading. */
    @Test
    fun vlcSeeksWithByteRanges() {
        assumeTrue("VLC not installed", Vlc.available(NoOpLogger))
        val clip = File(tmp.root, "big.mkv")
        val made = runCatching {
            ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=24",
                "-f", "lavfi", "-i", "sine=frequency=440", "-t", "30", "-c:v", "libx264", "-preset", "veryfast",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", clip.absolutePath).inheritIO().start().waitFor() == 0
        }.getOrDefault(false)
        assumeTrue("ffmpeg not available", made && clip.length() > 4_000_000)
        Harness(clip, tmp.newFolder("td"), bandwidth = 3L * 1024 * 1024).use { h ->
            // VLC reads up to 16 MB ahead by default and seeks inside that; a small read-ahead
            // makes this 9 MB file behave like a multi-GB movie, where a seek is a new range.
            h.play(":prefetch-buffer-size=256", ":prefetch-read-size=65536")
            h.waitUntil(20_000) { h.player.status().time() > 1_500 }
            val before = h.opens.size
            h.player.controls().setTime(22_000)
            h.waitUntil(20_000) { h.player.status().time() >= 22_500 }
            assertTrue(h.player.status().time() >= 22_500, "playing after the seek: ${h.player.status().time()} ms")
            // After the seek, a new range from the middle of the file (VLC's index reads are at the
            // very start and end; where 22 s lies depends on the encoding).
            val afterSeek = h.opens.drop(before)
            assertTrue(afterSeek.any { it in 1_000_000 until clip.length() - 1_000_000 }, "a range request for the seek: ${h.opens}")
            assertTrue(h.sim.downloadCalls.size >= 2, "the seek moved the Telegram download")
        }
    }

    private class Harness(clip: File, workDir: File, bandwidth: Long) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sim = SimulatedTelegramFiles(workDir).apply { bandwidthBytesPerSecond = bandwidth; latencyMs = 20; addFile(7, clip) }
        private val streamer = TelegramFileStreamer(sim, scope)
        val opens = CopyOnWriteArrayList<Long>()
        private val size = clip.length()
        val server = StreamServer(object : ByteSource {
            override suspend fun size(fileId: Int) = size
            override suspend fun open(fileId: Int, position: Long): ByteSource.Reader {
                opens += position
                val s = streamer.open(fileId, position)
                return object : ByteSource.Reader {
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = s.read(buffer, offset, length)
                    override fun close() = s.close()
                }
            }
        }, NoOpLogger)
        private val factory = MediaPlayerFactory("--vout=dummy", "--aout=dummy", "--no-video-title-show")
        val player: MediaPlayer = factory.mediaPlayers().newMediaPlayer()

        fun play(vararg options: String) {
            val playing = CountDownLatch(1)
            var failed = false
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) = playing.countDown()
                override fun error(mediaPlayer: MediaPlayer) { failed = true; playing.countDown() }
            })
            player.media().play(server.urlFor(7, "clip.mkv"), ":network-caching=1500", *options)
            assertTrue(playing.await(30, TimeUnit.SECONDS) && !failed, "VLC started playing")
        }

        fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end && !condition()) Thread.sleep(100)
        }

        override fun close() {
            player.controls().stop()
            player.release()
            factory.release()
            server.close()
            sim.close()
            scope.cancel()
        }
    }
}
