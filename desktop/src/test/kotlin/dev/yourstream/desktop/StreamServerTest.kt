package dev.yourstream.desktop

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.playback.ByteSource
import dev.yourstream.desktop.playback.StreamServer
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The local server VLC reads from, over real HTTP on 127.0.0.1, in front of the real
 * TelegramFileStreamer and a simulated TDLib download (parts, bandwidth, latency). This is a
 * simulation of Telegram, not a real account: real Telegram playback needs a Windows PC.
 */
class StreamServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sim: SimulatedTelegramFiles
    private lateinit var server: StreamServer
    private lateinit var bytes: ByteArray

    private fun start(size: Int = 3 * 1024 * 1024 + 123) {
        bytes = Random(7).nextBytes(size)
        val source = tmp.newFile("movie.mkv").apply { writeBytes(bytes) }
        sim = SimulatedTelegramFiles(tmp.newFolder("td")).apply { latencyMs = 5; bandwidthBytesPerSecond = 40L * 1024 * 1024 }
        sim.addFile(42, source)
        val streamer = TelegramFileStreamer(sim, scope)
        server = StreamServer(object : ByteSource {
            override suspend fun size(fileId: Int) = if (fileId == 42) bytes.size.toLong() else -1L
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
        if (::server.isInitialized) server.close()
        if (::sim.isInitialized) sim.close()
        scope.cancel()
    }

    private fun get(url: String, range: String? = null, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        // Header names are case-insensitive (the JDK server sends "Accept-ranges").
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            c.headerFields.forEach { (k, v) -> if (k != null) put(k, v.first()) }
        }
        val body = if (code < 400 && method == "GET") c.inputStream.use { it.readBytes() } else ByteArray(0)
        return Triple(code, headers, body)
    }

    @Test
    fun wholeFileWithoutRange() {
        start()
        val (code, headers, body) = get(server.urlFor(42, "Movie (2024) 1080p.mkv"))
        assertEquals(200, code)
        assertEquals("bytes", headers["Accept-Ranges"])
        assertEquals("video/x-matroska", headers["Content-Type"])
        assertContentEquals(bytes, body)
    }

    @Test
    fun seeksAreByteRangesReadWhileDownloading() {
        start()
        val url = server.urlFor(42, "movie.mkv")
        // What a player does: read the start, jump near the end (index), then into the middle.
        val (c1, h1, b1) = get(url, "bytes=0-65535")
        assertEquals(206, c1)
        assertEquals("bytes 0-65535/${bytes.size}", h1["Content-Range"])
        assertContentEquals(bytes.copyOfRange(0, 65536), b1)

        val end = bytes.size - 1000
        val (c2, _, b2) = get(url, "bytes=$end-")
        assertEquals(206, c2)
        assertContentEquals(bytes.copyOfRange(end, bytes.size), b2)

        val mid = bytes.size / 2 + 17
        val (c3, h3, b3) = get(url, "bytes=$mid-${mid + 300_000}")
        assertEquals(206, c3)
        assertEquals("300001", h3["Content-Length"])
        assertContentEquals(bytes.copyOfRange(mid, mid + 300_001), b3)

        // Last 500 bytes ("suffix" range).
        val (c4, _, b4) = get(url, "bytes=-500")
        assertEquals(206, c4)
        assertContentEquals(bytes.copyOfRange(bytes.size - 500, bytes.size), b4)
    }

    @Test
    fun headAndBadRequests() {
        start()
        val (code, headers, _) = get(server.urlFor(42), "bytes=100-", method = "HEAD")
        assertEquals(206, code)
        assertEquals((bytes.size - 100).toString(), headers["Content-Length"])
        assertEquals(416, get(server.urlFor(42), "bytes=${bytes.size + 10}-").first)
        // A path without this run's random token is refused.
        assertEquals(404, get("http://127.0.0.1:${server.port}/not-the-token/42/x.mkv").first)
    }

    @Test
    fun rangeParsing() {
        assertEquals(0L to 999L, StreamServer.parseRange(null, 1000))
        assertEquals(10L to 999L, StreamServer.parseRange("bytes=10-", 1000))
        assertEquals(10L to 20L, StreamServer.parseRange("bytes=10-20", 1000))
        assertEquals(10L to 999L, StreamServer.parseRange("bytes=10-5000", 1000))
        assertEquals(900L to 999L, StreamServer.parseRange("bytes=-100", 1000))
        assertEquals(0L to 999L, StreamServer.parseRange("bytes=-5000", 1000))
        assertNull(StreamServer.parseRange("bytes=1000-", 1000))
        assertNull(StreamServer.parseRange("bytes=20-10", 1000))
        assertEquals(5L to -1L, StreamServer.parseRange("bytes=5-", -1))
    }

    @Suppress("unused")
    private fun File.sizeOf() = length()
}
