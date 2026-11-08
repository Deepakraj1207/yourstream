package dev.yourstream.playback.stream

import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The range engine against a simulated Telegram backend in real time (bandwidth, latency and
 * outages are real delays). Numbers are printed as `METRIC` lines.
 */
class TelegramFileStreamerTest {

    private val dir = Files.createTempDirectory("stream-test").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"), partSize = 128 * 1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mb = 1024L * 1024L

    @After fun tearDown() { sim.close(); scope.cancel(); dir.deleteRecursively() }

    private fun source(name: String, size: Long, seed: Int = 7): File {
        val f = File(dir, name)
        RandomAccessFile(f, "rw").use { raf ->
            val rnd = Random(seed)
            val buf = ByteArray(1024 * 1024)
            var left = size
            while (left > 0) { rnd.nextBytes(buf); val n = minOf(left, buf.size.toLong()).toInt(); raf.write(buf, 0, n); left -= n }
        }
        return f
    }

    private fun expected(f: File, position: Long, length: Int): ByteArray =
        RandomAccessFile(f, "r").use { r -> ByteArray(length).also { r.seek(position); r.readFully(it) } }

    private fun streamer(config: StreamingConfig = StreamingConfig(defaultReadAheadBytes = 2 * mb, minReadAheadBytes = 1 * mb, stallTimeoutMs = 5_000), cache: StreamCache? = null) =
        TelegramFileStreamer(sim, scope, config, cache = cache)

    private suspend fun readAt(s: TelegramFileStreamer, id: Int, position: Long, length: Int): Pair<ByteArray, Long> {
        val stream = s.open(id, position)
        try {
            val buf = ByteArray(length)
            val n = stream.readFully(buf)
            assertEquals(length, n)
            return buf to stream.timeToFirstByteNanos!!
        } finally { stream.close() }
    }

    private fun metric(name: String, value: Any) = println("METRIC stream.$name = $value")

    @Test
    fun sequentialReadReturnsTheExactFile(): Unit = runBlocking {
        val src = source("a.bin", 5 * mb + 12345)
        sim.addFile(1, src)
        val s = streamer()
        val started = System.nanoTime()
        val stream = s.open(1, 0)
        val out = ByteArray(src.length().toInt())
        assertEquals(out.size, stream.readFully(out))
        assertEquals(-1, stream.read(ByteArray(10), 0, 10), "end of file")
        stream.close()
        assertContentEquals(src.readBytes(), out)
        val stats = s.stats(1)!!
        assertEquals(src.length(), stats.bytesServed)
        assertTrue(stats.directReads > 0 && stats.partReads == 0L, "fast path used")
        metric("sequential5MB.ms", (System.nanoTime() - started) / 1_000_000)
        metric("sequential5MB.downloadRequests", stats.downloadRequests.size)
    }

    @Test
    fun seekingFarAheadDoesNotDownloadTheGap(): Unit = runBlocking {
        val src = source("big.bin", 200 * mb)
        sim.addFile(1, src)
        sim.bandwidthBytesPerSecond = 20 * mb
        sim.latencyMs = 20
        val s = streamer()
        val (head, ttfbStart) = readAt(s, 1, 0, 256 * 1024)
        assertContentEquals(expected(src, 0, head.size), head)
        // "00:05 -> 01:20:00": jump to 94 % of the file.
        val target = 188 * mb + 777
        val (tail, ttfbSeek) = readAt(s, 1, target, 512 * 1024)
        assertContentEquals(expected(src, target, tail.size), tail)

        val f = sim.file(1)
        val gapParts = (4 * mb / sim.partSize).toInt() until ((target - 1 * mb) / sim.partSize).toInt()
        val fetchedInGap = f.downloadedParts.count { it in gapParts }
        assertEquals(0, fetchedInGap, "no byte between the old window and the seek target is downloaded")
        assertTrue(f.downloadedFraction() < 0.05, "only ${"%.1f".format(f.downloadedFraction() * 100)}% of the file downloaded")
        metric("startTtfb.ms", ttfbStart / 1_000_000)
        metric("seek188MB.ttfb.ms", ttfbSeek / 1_000_000)
        metric("seek188MB.downloadedPercent", "%.2f".format(f.downloadedFraction() * 100))
    }

    @Test
    fun repeatedRandomSeeksReturnCorrectBytesAndStayBounded(): Unit = runBlocking {
        val src = source("seek.bin", 120 * mb, seed = 3)
        sim.addFile(1, src)
        sim.bandwidthBytesPerSecond = 40 * mb
        sim.latencyMs = 10
        val s = streamer()
        val rnd = Random(42)
        val latencies = mutableListOf<Long>()
        repeat(25) {
            val pos = rnd.nextLong(0, src.length() - 200_000)
            val (bytes, ttfb) = readAt(s, 1, pos, 100_000)
            assertContentEquals(expected(src, pos, bytes.size), bytes, "seek #$it to $pos")
            latencies += ttfb / 1_000_000
        }
        // Rapid back-to-back seeks without reading much (scrubbing).
        repeat(10) { readAt(s, 1, rnd.nextLong(0, src.length() - 10_000), 1_000) }
        val fraction = sim.file(1).downloadedFraction()
        assertTrue(fraction < 0.35, "downloaded ${(fraction * 100).toInt()}% for 35 seeks")
        metric("randomSeek.ttfb.ms.median", latencies.sorted()[latencies.size / 2])
        metric("randomSeek.ttfb.ms.max", latencies.max())
        metric("randomSeek.downloadedPercent", "%.1f".format(fraction * 100))
    }

    @Test
    fun slowNetworkStillDeliversCorrectData(): Unit = runBlocking {
        val src = source("slow.bin", 3 * mb)
        sim.addFile(1, src)
        sim.bandwidthBytesPerSecond = 256 * 1024 // 2 Mbit/s
        sim.latencyMs = 150
        val s = streamer()
        val (bytes, ttfb) = readAt(s, 1, 0, 600 * 1024)
        assertContentEquals(expected(src, 0, bytes.size), bytes)
        assertTrue(ttfb / 1_000_000 >= 150, "first byte needs at least one round trip")
        metric("slowNetwork.ttfb.ms", ttfb / 1_000_000)
        metric("slowNetwork.waitMs", s.stats(1)!!.dataWaitMs)
    }

    @Test
    fun shortNetworkInterruptionIsBridged(): Unit = runBlocking {
        val src = source("int.bin", 8 * mb)
        sim.addFile(1, src)
        sim.bandwidthBytesPerSecond = 8 * mb
        val s = streamer()
        val stream = s.open(1, 0)
        val out = ByteArray(src.length().toInt())
        val reader = launch(Dispatchers.IO) { stream.readFully(out) }
        delay(200)
        sim.online = false
        delay(1_500)
        sim.online = true
        reader.join()
        stream.close()
        assertContentEquals(src.readBytes(), out)
        metric("interruption1500ms.totalWaitMs", s.stats(1)!!.dataWaitMs)
    }

    @Test
    fun longInterruptionFailsAsRetryableStallAndRecovers(): Unit = runBlocking {
        val src = source("stall.bin", 4 * mb)
        sim.addFile(1, src)
        sim.online = false
        val s = streamer(StreamingConfig(defaultReadAheadBytes = 2 * mb, stallTimeoutMs = 700))
        val started = System.nanoTime()
        val stream = s.open(1, 0)
        val e = assertFailsWith<TelegramStreamException.Stalled> { stream.read(ByteArray(10), 0, 10) }
        stream.close()
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue(e.retryable)
        assertTrue(waited in 650..2_500, "stall detected after ${waited}ms")
        sim.online = true
        val (bytes, _) = readAt(s, 1, 0, 1000)
        assertContentEquals(expected(src, 0, 1000), bytes, "a retry after the network returns succeeds")
        metric("stallDetection.ms", waited)
    }

    @Test
    fun unavailableFilesFailFastAndAreNotRetryable(): Unit = runBlocking {
        sim.addFile(1, source("u.bin", mb))
        sim.makeUnavailable(1)
        val s = streamer()
        val e = assertFailsWith<TelegramStreamException.Unavailable> { s.open(1, 0) }
        assertTrue(!e.retryable)
        assertFailsWith<TelegramStreamException.Unavailable> { s.open(99, 0) }
        sim.addFile(2, source("v.bin", mb))
        assertFailsWith<TelegramStreamException.OutOfRange> { s.open(2, 2 * mb) }
    }

    @Test
    fun interruptingABlockedReaderCancelsPromptly() {
        val src = source("c.bin", 2 * mb)
        sim.addFile(1, src)
        sim.online = false
        val s = streamer()
        var failure: Throwable? = null
        val t = Thread {
            try { runBlocking { s.open(1, 0).read(ByteArray(10), 0, 10) } } catch (e: Throwable) { failure = e }
        }
        t.start()
        Thread.sleep(300)
        val started = System.nanoTime()
        t.interrupt()
        t.join(2_000)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(!t.isAlive, "reader thread ended")
        assertTrue(failure is InterruptedException, "got $failure")
        metric("cancelLatency.ms", ms)
    }

    @Test
    fun pausedReaderLimitsBackgroundDownloadToTheWindow(): Unit = runBlocking {
        val src = source("p.bin", 64 * mb)
        sim.addFile(1, src)
        val s = streamer(StreamingConfig(defaultReadAheadBytes = 4 * mb, minReadAheadBytes = 1 * mb))
        val stream = s.open(1, 0)
        stream.readFully(ByteArray(100_000))
        delay(800) // reader idle, unlimited bandwidth
        val downloaded = sim.file(1).downloadedFraction() * src.length()
        assertTrue(downloaded <= 4 * mb + sim.partSize, "downloaded ${downloaded / mb} MB while paused")
        // Reading on extends the window.
        stream.readFully(ByteArray((3 * mb).toInt()))
        delay(300)
        assertTrue(sim.file(1).downloadedFraction() * src.length() > 4 * mb, "window extended: ${sim.downloadCalls} ${s.stats(1)}")
        stream.close()
        metric("pausedWindow.downloadedMB", "%.1f".format(downloaded / mb))
    }

    @Test
    fun seekingBackIntoDownloadedDataNeedsNoNetwork(): Unit = runBlocking {
        val src = source("b.bin", 6 * mb)
        sim.addFile(1, src)
        val s = streamer()
        readAt(s, 1, 0, (2 * mb).toInt())
        sim.online = false
        val (bytes, ttfb) = readAt(s, 1, 512 * 1024, 64 * 1024)
        assertContentEquals(expected(src, 512 * 1024, bytes.size), bytes)
        assertTrue(ttfb / 1_000_000 < 100)
        metric("seekBackCached.ttfb.ms", ttfb / 1_000_000)
    }

    @Test
    fun completeFilesAreReadWithoutAnyDownload(): Unit = runBlocking {
        val src = source("d.bin", 3 * mb)
        sim.addFile(1, src, alreadyDownloaded = true)
        val s = streamer()
        val (bytes, _) = readAt(s, 1, mb, 1000)
        assertContentEquals(expected(src, mb, 1000), bytes)
        assertTrue(sim.downloadCalls.isEmpty())
    }

    @Test
    fun readPartFallbackWhenTheFileCannotBeReadDirectly(): Unit = runBlocking {
        val src = source("e.bin", 2 * mb)
        sim.addFile(1, src)
        val s = streamer(StreamingConfig(defaultReadAheadBytes = 2 * mb, directFileReads = false))
        val (bytes, _) = readAt(s, 1, 100, (mb).toInt())
        assertContentEquals(expected(src, 100, bytes.size), bytes)
        assertTrue(s.stats(1)!!.partReads > 0)
    }

    @Test
    fun releaseCancelsTheDownloadAndTheCacheEvictsOldStreams(): Unit = runBlocking {
        sim.addFile(1, source("f1.bin", 8 * mb))
        sim.addFile(2, source("f2.bin", 8 * mb))
        sim.addFile(3, source("f3.bin", mb), alreadyDownloaded = true)
        sim.bandwidthBytesPerSecond = 8 * mb // downloads still running when playback stops
        var now = 0L
        val cache = StreamCache(sim, budgetBytes = mb, clock = { now++ })
        val s = streamer(cache = cache)
        readAt(s, 1, 0, (mb).toInt())
        s.release(1)
        assertTrue(1 in sim.cancelCalls, "download cancelled on release")
        assertTrue(sim.deleteCalls.isEmpty(), "the newest streamed copy is kept even above the budget")
        readAt(s, 2, 0, (mb).toInt())
        s.release(2)
        readAt(s, 3, 0, 1000)
        s.release(3)
        assertEquals(listOf(1), sim.deleteCalls.toList(), "oldest streamed copy evicted; newest kept; the user's complete download never touched")
    }

}
