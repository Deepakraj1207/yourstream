package dev.yourstream.playback.adaptive

import dev.yourstream.core.common.AppResult
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val MiB = 1024L * 1024L

/** Real-time tests against the simulated Telegram backend. */
class GovernorAndPrefetchTest {
    private val dir = Files.createTempDirectory("governor").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() { sim.close(); scope.cancel(); dir.deleteRecursively() }

    private fun file(id: Int, size: Long) = File(dir, "f$id").also { f -> RandomAccessFile(f, "rw").use { it.setLength(size) } }.also { sim.addFile(id, it) }

    private fun activeInSim() = (1..20).count { id -> runCatching { runBlocking { (sim.getFile(id) as AppResult.Ok).value.isDownloadingActive } }.getOrDefault(false) }

    @Test
    fun neverMoreThanTheLimitAndPlaybackPreemptsPrefetch(): Unit = runBlocking {
        (1..6).forEach { file(it, 64 * MiB) }
        sim.bandwidthBytesPerSecond = 2 * MiB
        val g = DownloadGovernor(sim, scope, TransferLimits(maxConcurrentDownloads = 2, maxConcurrentPrefetches = 1))
        assertIs<AppResult.Ok<*>>(g.download(1, 4, 0, 0))                           // prefetch
        assertIs<AppResult.Err>(g.download(2, 4, 0, 0), "second prefetch over its own limit")
        assertIs<AppResult.Ok<*>>(g.download(3, 32, 0, 0))                          // playback
        assertIs<AppResult.Ok<*>>(g.download(4, 32, 0, 0), "another playback preempts the prefetch")
        assertTrue(1 in sim.cancelCalls)
        assertIs<AppResult.Err>(g.download(5, 4, 0, 0), "no free slot for prefetch")
        repeat(10) { g.download(6, 32, it * MiB, 0) } // re-requests of an admitted file do not take more slots
        delay(100)
        assertTrue(activeInSim() <= 2, "at most 2 downloads running: ${activeInSim()}")
        assertTrue(g.stats.value.peakConcurrent <= 2)
        assertTrue(g.stats.value.preempted >= 1 && g.stats.value.rejected >= 2)
    }

    @Test
    fun pausedPrefetchIsCancelledAndRefused(): Unit = runBlocking {
        file(1, 32 * MiB)
        sim.bandwidthBytesPerSecond = MiB
        val g = DownloadGovernor(sim, scope)
        g.download(1, 4, 0, 0)
        g.pausePrefetch("seek")
        assertTrue(1 in sim.cancelCalls)
        assertIs<AppResult.Err>(g.download(1, 4, 0, 0))
        g.resumePrefetch("seek")
        assertIs<AppResult.Ok<*>>(g.download(1, 4, 0, 0))
    }

    @Test
    fun prefetchFetchesOnlyHeadAndTailOfTheNextEpisodeNearTheEnd(): Unit = runBlocking {
        val next = file(2, 200 * MiB)
        sim.bandwidthBytesPerSecond = 16 * MiB
        val g = DownloadGovernor(sim, scope)
        val provider = NextMediaProvider { PrefetchCandidate(2, next.length(), 1_440_000, "S01E02") }
        val p = PrefetchManager(g, provider, scope, PrefetchConfig(pollMs = 30))
        val allowed = BufferPolicy.static().copy(prefetchAllowed = true)
        val duration = 1_440_000L
        // Far from the end: nothing.
        p.onSample(PlaybackSample(0, PlaybackPhase.PLAYING, 600_000, 650_000, duration, 150_000), allowed)
        delay(200)
        assertEquals(PrefetchStatus.IDLE, p.state.status)
        // Near the end, but the policy forbids it (e.g. rebuffer risk): wait.
        p.onSample(PlaybackSample(0, PlaybackPhase.PLAYING, 1_350_000, 1_400_000, duration, 150_000), allowed.copy(prefetchAllowed = false))
        assertEquals(PrefetchStatus.WAITING, p.state.status)
        p.onSample(PlaybackSample(0, PlaybackPhase.PLAYING, 1_350_000, 1_400_000, duration, 150_000), allowed)
        val deadline = System.currentTimeMillis() + 10_000
        while (p.state.status != PrefetchStatus.DONE && System.currentTimeMillis() < deadline) delay(50)
        assertEquals(PrefetchStatus.DONE, p.state.status, p.state.toString())
        val f = sim.file(2)
        val bitrate = next.length() * 1000 / 1_440_000
        assertTrue(f.prefix(0) >= minOf(bitrate * 20, 12 * MiB), "head on the device")
        assertTrue(f.isDownloaded(next.length() - 1), "tail (container index) on the device")
        assertTrue(f.downloadedFraction() < 0.10, "not the whole episode: ${(f.downloadedFraction() * 100).toInt()}%")
        p.onPlaying(2)
        assertTrue(p.state.used)
    }
}
