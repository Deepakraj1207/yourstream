package dev.yourstream.desktop

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.playback.ByteSource
import dev.yourstream.desktop.playback.StreamServer
import dev.yourstream.desktop.playback.VideoPlayerController
import dev.yourstream.desktop.playback.VlcEngine
import dev.yourstream.playback.stream.StreamHints
import dev.yourstream.playback.stream.StreamingConfig
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Time to first picture and to resume after a seek, through the whole chain: real VLC → stream
 * server → streamer → simulated Telegram (TDLib-like parts, per-part latency, bandwidth limit).
 * A measurement, not a pass/fail test: run with -Dbench=true and a clip at -Dbench.clip=...
 * (made with ffmpeg; see docs/WINDOWS.md).
 */
class StreamingBenchmark {
    data class Setup(val name: String, val config: StreamingConfig, val hints: Boolean, val cachingMs: Int = 1500, val warm: Boolean = false)

    @Test
    fun measure() {
        assumeTrue("benchmark: run with -Dbench=true", System.getProperty("bench") == "true")
        val clip = File(System.getProperty("bench.clip") ?: "/tmp/claude-0/bench-1080p.mkv")
        assumeTrue("clip missing", clip.isFile)
        val engine = VlcEngine(NoOpLogger)
        val status = runBlocking { engine.start() }
        assumeTrue(status is VlcEngine.Status.Ready)
        val factory = (status as VlcEngine.Status.Ready).factory
        val setups = listOf(
            Setup("0.13.2 (cache 1500 ms)", StreamingConfig(), hints = false, cachingMs = 1500),
            Setup("cache 600 ms", StreamingConfig(), hints = true, cachingMs = 600),
            Setup("cache 600 ms + warm-up", StreamingConfig(), hints = true, cachingMs = 600, warm = true),
        )
        val bandwidth = (System.getProperty("bench.mbps") ?: "24").toLong() * 1024 * 1024 / 8
        val latency = (System.getProperty("bench.latency") ?: "150").toLong()
        println("clip ${clip.length() shr 20} MB, ${bandwidth shr 10} KB/s, $latency ms per part")
        for (s in setups) repeat(2) { run ->
            val dir = Files.createTempDirectory("bench").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sim = SimulatedTelegramFiles(dir, partSize = 512 * 1024).apply { bandwidthBytesPerSecond = bandwidth; latencyMs = latency; addFile(5, clip) }
            val streamer = TelegramFileStreamer(sim, scope, s.config)
            val server = StreamServer(object : ByteSource {
                override suspend fun size(fileId: Int) = clip.length()
                override suspend fun open(fileId: Int, position: Long): ByteSource.Reader {
                    val st = streamer.open(fileId, position, if (s.hints) StreamHints(clip.length(), 90_000) else StreamHints())
                    return object : ByteSource.Reader {
                        override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = st.read(buffer, offset, length)
                        override fun close() = st.close()
                    }
                }
            }, NoOpLogger)
            if (s.warm) {
                // The title page was open for a few seconds before Play.
                dev.yourstream.desktop.playback.StartWarmer(sim, scope, NoOpLogger).warm(5, clip.length())
                Thread.sleep(4_000)
            }
            val p = VideoPlayerController(factory, NoOpLogger)
            val t0 = System.nanoTime()
            p.play(server.urlFor(5, "bench.mkv"), networkCachingMs = s.cachingMs)
            fun waitFor(timeoutMs: Long, c: () -> Boolean): Long? {
                val start = System.nanoTime()
                while ((System.nanoTime() - start) / 1_000_000 < timeoutMs) { if (c()) return (System.nanoTime() - start) / 1_000_000; Thread.sleep(20) }
                return null
            }
            val firstFrame = waitFor(60_000) { p.frames.frameId.longValue > 0 }?.let { (System.nanoTime() - t0) / 1_000_000 }
            waitFor(10_000) { p.state.value.timeMs > 2_000 }
            val before = p.state.value.timeMs
            val t1 = System.nanoTime()
            val frames0 = p.frames.frameId.longValue
            p.seekTo(60_000)
            val seek = waitFor(60_000) { p.state.value.timeMs >= 60_500 && p.frames.frameId.longValue > frames0 + 5 }?.let { (System.nanoTime() - t1) / 1_000_000 }
            // Smoothness: picture updates in 5 s of playing after the seek (24 fps source).
            val f0 = p.frames.frameId.longValue
            Thread.sleep(5_000)
            val fps = (p.frames.frameId.longValue - f0) / 5.0
            println("%-28s run %d: first picture %s ms, seek resumed in %s ms, then %.1f fps, %d MB downloaded".format(
                s.name, run + 1, firstFrame, seek, fps, sim.bytesDownloaded.get() shr 20))
            p.close(); server.close(); sim.close(); scope.cancel(); dir.deleteRecursively()
            Thread.sleep(500)
        }
        engine.close()
    }
}
