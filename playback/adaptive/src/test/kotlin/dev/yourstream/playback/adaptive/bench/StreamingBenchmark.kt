package dev.yourstream.playback.adaptive.bench

import dev.yourstream.playback.adaptive.AdaptiveStreaming
import dev.yourstream.playback.adaptive.BufferManager
import dev.yourstream.playback.adaptive.BufferPolicy
import dev.yourstream.playback.adaptive.Connectivity
import dev.yourstream.playback.adaptive.ConnectionType
import dev.yourstream.playback.adaptive.DefaultNetworkMonitor
import dev.yourstream.playback.adaptive.DeviceProfile
import dev.yourstream.playback.adaptive.DownloadGovernor
import dev.yourstream.playback.adaptive.NextMediaProvider
import dev.yourstream.playback.adaptive.PlaybackPhase
import dev.yourstream.playback.adaptive.PrefetchCandidate
import dev.yourstream.playback.adaptive.PrefetchConfig
import dev.yourstream.playback.adaptive.PrefetchManager
import dev.yourstream.playback.adaptive.SeekConfig
import dev.yourstream.playback.adaptive.SeekManager
import dev.yourstream.playback.adaptive.SeekTarget
import dev.yourstream.playback.adaptive.TransferLimits
import dev.yourstream.playback.stream.RangeSet
import dev.yourstream.playback.stream.StreamingConfig
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.stream.Ticker
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale
import kotlin.random.Random

private const val K = 20 // benchmark runs 20× faster than the media it models
private const val KB = 1024L
private const val MB = 1024L * 1024L
private const val BITRATE = 250 * KB // 2 Mbit/s video
private const val EPISODE_MS = 1_440_000L // 24 min

/** One network condition for [durationMs] of media time. */
data class Segment(val durationMs: Long, val bytesPerSecond: Long, val latencyMs: Long, val type: ConnectionType, val online: Boolean = true)

data class Result(
    val scenario: String, val config: String,
    val startupMs: Long?, val seekMedianMs: Long?, val seekP90Ms: Long?, val seeks: Int,
    val rebuffers: Int, val rebufferMs: Long, val playedMs: Long, val playerErrors: Int, val loadErrors: Int,
    val downloadedBytes: Long, val playedBytes: Long, val peakBufferBytes: Long, val peakConcurrent: Int, val peakHeapMb: Long,
    val nextStartupMs: Long? = null, val prefetchedBytes: Long = 0,
) {
    val wasteBytes get() = (downloadedBytes - playedBytes).coerceAtLeast(0)
    val rebuffersPerHour get() = if (playedMs == 0L) 0.0 else rebuffers * 3_600_000.0 / playedMs
}

/**
 * Baseline (Phase 7: fixed buffer policy, full read-ahead window from the first request, no
 * offline awareness, no seek coalescing, no prefetch, no download limits) against the adaptive
 * system on the same scripted network traces and user actions.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class StreamingBenchmark {

    private inner class Run(val adaptive: Boolean, val device: DeviceProfile = DeviceProfile()) : AutoCloseable {
        val clock = TimeScale(K)
        val dir: File = Files.createTempDirectory("bench").toFile()
        val sim = SimulatedTelegramFiles(File(dir, "tdlib"), partSize = 512 * 1024)
        val dispatcher = newFixedThreadPoolContext(4, "bench")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val conn = MutableStateFlow(Connectivity.WIFI)
        val telegram = MutableStateFlow(true)
        val governor = if (adaptive) DownloadGovernor(sim, scope, TransferLimits()) else null
        val streamer = TelegramFileStreamer(
            governor ?: sim, scope,
            StreamingConfig(stallTimeoutMs = 20_000, pollIntervalMs = maxOf(5, 150L / K), initialWindowBytes = if (adaptive) 4 * MB else 0),
            ticker = Ticker { clock.nanos() },
        )
        val monitor = if (adaptive) DefaultNetworkMonitor(conn, telegram, scope, clock::nowMs) else null
        val buffer = BufferManager(device)
        val prefetch = governor?.let {
            PrefetchManager(it, NextMediaProvider { PrefetchCandidate(2, EPISODE_MS * BITRATE / 1000, EPISODE_MS, "next") }, scope, PrefetchConfig(pollMs = maxOf(5, 250L / K)))
        }
        val engine = if (adaptive) AdaptiveStreaming(streamer, governor!!, monitor!!, buffer, scope, prefetch).also { AdaptiveStreaming.connect(streamer, monitor) } else null
        val staticPolicy = BufferPolicy.static(streamer.config.readAheadFor(dev.yourstream.playback.stream.StreamHints(EPISODE_MS * BITRATE / 1000, EPISODE_MS)))
        val played = mutableMapOf<Int, RangeSet>()
        var peakConcurrent = 0
        var peakHeap = 0L
        var player: VirtualPlayer? = null
        var seekManager: SeekManager? = null

        init {
            for (id in 1..2) File(dir, "ep$id").also { f -> RandomAccessFile(f, "rw").use { it.setLength(EPISODE_MS * BITRATE / 1000) }; sim.addFile(id, f) }
            Runtime.getRuntime().gc()
            val heapBase = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            scope.launch {
                while (true) {
                    peakConcurrent = maxOf(peakConcurrent, sim.activeDownloads())
                    peakHeap = maxOf(peakHeap, Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - heapBase)
                    delay(5)
                }
            }
        }

        fun policy(): BufferPolicy = engine?.policy ?: staticPolicy

        fun network(trace: List<Segment>, repeat: Boolean = false) = scope.launch {
            do {
                for (s in trace) {
                    sim.bandwidthBytesPerSecond = s.bytesPerSecond * K
                    sim.latencyMs = s.latencyMs / K
                    sim.online = s.online
                    conn.value = if (!s.online) Connectivity.NONE else Connectivity(s.type, metered = s.type == ConnectionType.CELLULAR)
                    telegram.value = s.online
                    delay(clock.realMs(s.durationMs))
                }
            } while (repeat)
        }

        fun play(fileId: Int, fromMs: Long): VirtualPlayer {
            val p = VirtualPlayer(
                streamer, fileId, EPISODE_MS * BITRATE / 1000, EPISODE_MS, clock, scope, ::policy,
                onSample = { s ->
                    engine?.onSample(s, fileId)
                    if (System.getenv("BENCH_TRACE") != null && s.nowMs / 500 % 10 == 0L) {
                        val pol = policy()
                        println("TRACE t=${s.nowMs / 1000}s ${s.phase} ahead=${s.bufferedAheadMs / 1000}s loading=${s.loading} est=${monitor?.state?.value?.bytesPerSecond?.div(1024)}KB/s q=${monitor?.state?.value?.quality} start=${pol.startPlaybackMs} resume=${pol.resumeAfterRebufferMs} min=${pol.minBufferMs} max=${pol.maxBufferMs} ra=${pol.readAheadBytes / MB}MB risk=${pol.risk} win=${streamer.stats(fileId)?.windowBytes?.div(MB)} :: ${pol.reason}")
                    }
                    if (s.phase == PlaybackPhase.PLAYING) seekManager?.onSeekCompleted()
                },
                played = played.getOrPut(fileId) { RangeSet() },
            )
            player = p
            seekManager = if (adaptive) SeekManager(
                object : SeekTarget {
                    override fun applySeek(positionMs: Long) = p.seek(positionMs)
                    override fun setFastSeek(enabled: Boolean) = Unit
                },
                scope, { System.nanoTime() / 1_000_000 }, governor, { streamer.focus(fileId).size },
                SeekConfig(coalesceWindowMs = maxOf(1, 150L / K), scrubIntervalMs = maxOf(1, 300L / K)),
            ) else null
            prefetch?.onPlaying(fileId)
            p.start(fromMs)
            return p
        }

        fun seek(toMs: Long) { seekManager?.seekTo(toMs) ?: player!!.seek(toMs) }

        suspend fun watch(mediaMs: Long) = delay(clock.realMs(mediaMs))

        suspend fun untilEnded(p: VirtualPlayer, maxMediaMs: Long) {
            val end = clock.nowMs() + maxMediaMs
            while (!p.isEnded && clock.nowMs() < end) delay(5)
        }

        fun result(name: String, main: VirtualPlayer, next: VirtualPlayer? = null): Result {
            val all = listOfNotNull(main, next)
            val seeks = main.seekLatencies.sorted()
            return Result(
                scenario = name, config = (if (adaptive) "adaptive" else "baseline") + if (device.lowRam) " (low-RAM device)" else "",
                startupMs = main.startupMs, seekMedianMs = seeks.getOrNull(seeks.size / 2), seekP90Ms = seeks.getOrNull((seeks.size * 9) / 10),
                seeks = seeks.size, rebuffers = all.sumOf { it.rebuffers }, rebufferMs = all.sumOf { it.rebufferMs },
                playedMs = all.sumOf { it.playedMs }, playerErrors = all.sumOf { it.playerErrors }, loadErrors = all.sumOf { it.loadErrors },
                downloadedBytes = sim.bytesDownloaded.get(), playedBytes = played.values.sumOf { it.total() },
                peakBufferBytes = all.maxOf { it.peakBufferBytes }, peakConcurrent = peakConcurrent, peakHeapMb = peakHeap / MB,
                nextStartupMs = next?.startupMs, prefetchedBytes = prefetch?.state?.prefetchedBytes ?: 0,
            )
        }

        override fun close() {
            player?.stop()
            scope.cancel(); dispatcher.close(); sim.close(); dir.deleteRecursively()
        }
    }

    private fun bench(name: String, device: DeviceProfile = DeviceProfile(), adaptiveOnly: Boolean = false, script: suspend Run.() -> Result) {
        for (adaptive in listOf(false, true)) {
            if (adaptiveOnly && !adaptive) continue
            val r = Run(adaptive, device).use { run -> runBlocking { run.script() } }
            results += r
            println("BENCH ${r.scenario} [${r.config}] startup=${r.startupMs} seekMed=${r.seekMedianMs} rebuffers=${r.rebuffers} " +
                "errors=${r.playerErrors} downloaded=${r.downloadedBytes / MB}MB played=${r.playedBytes / MB}MB waste=${r.wasteBytes / MB}MB " +
                "peakBuf=${r.peakBufferBytes / MB}MB conc=${r.peakConcurrent} next=${r.nextStartupMs}")
        }
    }

    private val wifi = { bps: Long, lat: Long -> Segment(3_600_000, bps, lat, ConnectionType.WIFI) }

    @Test fun wifiStable() = bench("Wi-Fi, stable 20 Mbit/s") {
        network(listOf(wifi(2_500 * KB, 20)))
        val p = play(1, 0); watch(240_000); result("Wi-Fi, stable 20 Mbit/s", p)
    }

    @Test fun mobileFluctuating() = bench("Mobile, fluctuating 0.5-4 Mbit/s") {
        network(
            // Average ≈ 290 KB/s for a 250 KB/s video, with 30 s dips to a third of the bitrate.
            listOf(500 * KB, 90 * KB, 350 * KB, 60 * KB, 450 * KB).map { Segment(30_000, it, 150, ConnectionType.CELLULAR) }, repeat = true,
        )
        val p = play(1, 0); watch(300_000); result("Mobile, fluctuating 0.5-4 Mbit/s", p)
    }

    @Test fun switchAndDropout() = bench("Wi-Fi -> 100 s outage -> mobile") {
        // The outage starts early (little buffered) and outlasts the buffer: the player must wait it out.
        network(listOf(Segment(10_000, 1_500 * KB, 20, ConnectionType.WIFI), Segment(100_000, 0, 0, ConnectionType.NONE, online = false), Segment(3_600_000, 450 * KB, 100, ConnectionType.CELLULAR)))
        val p = play(1, 0); watch(300_000); result("Wi-Fi -> 100 s outage -> mobile", p)
    }

    @Test fun poorNetwork() = bench("Poor mobile, 2.2 Mbit/s for 2 Mbit/s video") {
        network(listOf(Segment(3_600_000, 275 * KB, 250, ConnectionType.CELLULAR)))
        val p = play(1, 0); watch(240_000); result("Poor mobile, 2.2 Mbit/s for 2 Mbit/s video", p)
    }

    @Test fun poorNetworkLowRamDevice() = bench("Poor mobile, low-RAM device", DeviceProfile(memoryClassMb = 128, lowRam = true, cpuCores = 4), adaptiveOnly = true) {
        network(listOf(Segment(3_600_000, 275 * KB, 250, ConnectionType.CELLULAR)))
        val p = play(1, 0); watch(240_000); result("Poor mobile, low-RAM device", p)
    }

    @Test fun seekHeavy() = bench("Seek-heavy (5 bursts of 8 rapid seeks + 5 single seeks)") {
        network(listOf(wifi(1_200 * KB, 40)))
        val rnd = Random(11)
        val p = play(1, 0)
        watch(20_000)
        repeat(5) {
            repeat(8) { seek(rnd.nextLong(0, EPISODE_MS - 60_000)); watch(40) }
            watch(15_000)
        }
        repeat(5) { seek(rnd.nextLong(0, EPISODE_MS - 60_000)); watch(15_000) }
        result("Seek-heavy (5 bursts of 8 rapid seeks + 5 single seeks)", p)
    }

    @Test fun nextEpisode() = bench("Next episode (watch last 150 s, continue)") {
        network(listOf(wifi(2_000 * KB, 30)))
        val p1 = play(1, EPISODE_MS - 150_000)
        untilEnded(p1, 400_000)
        p1.stop(); streamer.release(1)
        val p2 = play(2, 0)
        watch(30_000)
        result("Next episode (watch last 150 s, continue)", p1, p2)
    }

    @Test fun nextEpisodeAbandoned() = bench("Next episode prefetched, user stops") {
        network(listOf(wifi(2_000 * KB, 30)))
        val p1 = play(1, EPISODE_MS - 150_000)
        untilEnded(p1, 400_000)
        watch(5_000)
        result("Next episode prefetched, user stops", p1)
    }

    companion object {
        private val results = java.util.concurrent.CopyOnWriteArrayList<Result>()

        @JvmStatic @AfterClass fun report() {
            if (results.isEmpty()) return
            val f = { v: Long? -> v?.let { String.format(Locale.US, "%,d", it) } ?: "–" }
            val mb = { v: Long -> String.format(Locale.US, "%.1f", v / MB.toDouble()) }
            val sb = StringBuilder()
            sb.appendLine("| Scenario | Config | Startup ms | Seek ms (median / p90, n) | Rebuffers (per h) | Stall time ms | Load errors / shown | Downloaded MB | Played MB | Waste MB (%) | Peak RAM buffer MB | Peak JVM heap Δ MB | Peak concurrent downloads | Next-episode startup ms |")
            sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (r in results.sortedWith(compareBy({ it.scenario }, { it.config }))) {
                val wastePct = if (r.downloadedBytes == 0L) 0.0 else r.wasteBytes * 100.0 / r.downloadedBytes
                sb.appendLine(
                    "| ${r.scenario} | ${r.config} | ${f(r.startupMs)} | ${if (r.seeks == 0) "–" else "${f(r.seekMedianMs)} / ${f(r.seekP90Ms)} (${r.seeks})"} | " +
                        "${r.rebuffers} (${String.format(Locale.US, "%.1f", r.rebuffersPerHour)}) | ${f(r.rebufferMs)} | ${r.loadErrors} / ${r.playerErrors} | ${mb(r.downloadedBytes)} | ${mb(r.playedBytes)} | " +
                        "${mb(r.wasteBytes)} (${String.format(Locale.US, "%.0f", wastePct)}%) | ${mb(r.peakBufferBytes)} | ${r.peakHeapMb} | ${r.peakConcurrent} | ${f(r.nextStartupMs)} |",
                )
            }
            val out = File("build/reports/benchmarks/streaming-benchmark.md")
            out.parentFile.mkdirs()
            out.writeText("# Streaming benchmark\n\nMedia: 2 Mbit/s, 24-min episodes; times are media time (run ${K}× faster).\n\n$sb")
            println(sb)
        }
    }
}
