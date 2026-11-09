package dev.yourstream.playback.torrent

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.libtorrent4j.LibTorrent
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real engine against a real peer: a libtorrent session on localhost seeds a 48 MB file and
 * the engine streams it as it would an add-on's torrent result (file list from the peer, pieces
 * fetched around the read position, reads through [TorrentDataSource]). Uses libtorrent's Linux
 * build; skipped where that cannot load.
 */
@RunWith(RobolectricTestRunner::class)
class TorrentEngineLocalTest {
    @get:Rule val tmp = TemporaryFolder()


    @Test
    fun streamsAFileFromALocalSeed() = runBlocking {
        assumeTrue("libtorrent's native library is not available here", nativeReady)
        val seedDir = tmp.newFolder("seed")
        val data = ByteArray(48 shl 20).also { Random(7).nextBytes(it) }
        val film = File(seedDir, "Film (2020).mkv").apply { writeBytes(data) }
        val info = TorrentInfo.bdecode(singleFileTorrent(film.name, data, pieceLength = 256 shl 10))

        val seeder = SessionManager(false)
        val settings = SettingsPack().listenInterfaces("127.0.0.1:$SEED_PORT")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        // File I/O like the engine: libtorrent's mmap storage installs a SIGSEGV handler that would
        // take over the JVM's own (Android's runtime chains handlers; a desktop JVM does not).
        seeder.start(SessionParams(settings).apply { setPosixDiskIO() })
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = TorrentEngine(context)
        try {
            seeder.download(info, seedDir)
            withTimeout(30_000) { while (seeder.find(info.infoHash())?.status()?.isSeeding != true) delay(100) }
            val phases = mutableListOf<TorrentPhase>()
            val stream = withTimeout(60_000) {
                engine.open(TorrentRequest(info.infoHash().toHex(), sources = listOf("peer:127.0.0.1:$SEED_PORT"))) { phases += it.phase }
            }
            // From a local seed the file list can arrive before the first progress report.
            assertEquals(TorrentPhase.STARTING, phases.first(), "progress is reported from the start: $phases")
            assertEquals("Film (2020).mkv", stream.fileName)
            assertEquals(data.size.toLong(), stream.size)

            // On another thread: the reads below block this one.
            val maxPeers = java.util.concurrent.atomic.AtomicInteger()
            val watcher = launch(Dispatchers.Default) { stream.stats.collect { st -> maxPeers.accumulateAndGet(st.peers, ::maxOf) } }
            val started = System.nanoTime()
            val ds = TorrentDataSource(engine::source)
            val uri = TorrentUri.of(stream.key, stream.fileName)
            // Like Media3 preparing an MKV: the end of the file (its index) first…
            val tailAt = data.size - (512 shl 10).toLong()
            ds.open(DataSpec.Builder().setUri(uri).setPosition(tailAt).build())
            val tail = readAll(ds)
            ds.close()
            assertContentEquals(data.copyOfRange(tailAt.toInt(), data.size), tail)

            // …then the whole file from the start, past the first read-ahead window (32 MB).
            ds.open(DataSpec.Builder().setUri(uri).build())
            val all = readAll(ds)
            ds.close()
            assertEquals(data.size, all.size)
            assertContentEquals(data, all, "every byte arrives intact and in order")

            println("streamed ${data.size shr 20} MB in ${(System.nanoTime() - started) / 1_000_000} ms")
            withTimeout(10_000) { while (stream.stats.value.progress < 1f) delay(100) }
            assertEquals(TorrentPhase.COMPLETE, stream.stats.value.phase)
            watcher.cancel()
            assertTrue(maxPeers.get() >= 1, "the seed was counted while streaming")
            engine.close(stream)
            val dir = File(context.cacheDir, "torrents/${stream.key}")
            withTimeout(15_000) { while (dir.exists()) delay(200) }
        } finally {
            engine.shutdown()
            seeder.stop()
        }
    }

    /**
     * A .torrent for one file, written here rather than by libtorrent's builder: that hashes
     * through memory-mapped files, which installs libtorrent's SIGSEGV handler in this JVM.
     */
    private fun singleFileTorrent(name: String, data: ByteArray, pieceLength: Int): ByteArray {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val pieces = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < data.size) {
            val n = minOf(pieceLength, data.size - off)
            sha1.update(data, off, n)
            pieces.write(sha1.digest())
            off += n
        }
        val out = java.io.ByteArrayOutputStream()
        fun str(b: ByteArray) { out.write("${b.size}:".toByteArray()); out.write(b) }
        fun str(t: String) = str(t.toByteArray())
        fun int(v: Long) = out.write("i${v}e".toByteArray())
        // Bencoded dictionaries list their keys in sorted order.
        out.write('d'.code); str("info")
        out.write('d'.code)
        str("length"); int(data.size.toLong())
        str("name"); str(name)
        str("piece length"); int(pieceLength.toLong())
        str("pieces"); str(pieces.toByteArray())
        out.write('e'.code)
        out.write('e'.code)
        return out.toByteArray()
    }

    private fun readAll(ds: TorrentDataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val SEED_PORT = 47011

        /** libtorrent's Linux library from the test classpath, extracted where it can be loaded. */
        val nativeReady: Boolean by lazy {
            runCatching {
                if (System.getProperty("os.arch") !in setOf("amd64", "x86_64")) return@runCatching false
                val res = TorrentEngineLocalTest::class.java.classLoader!!.getResource("lib/x86_64/libtorrent4j.so") ?: return@runCatching false
                val out = File.createTempFile("libtorrent4j", ".so").apply { deleteOnExit() }
                res.openStream().use { i -> out.outputStream().use { i.copyTo(it) } }
                System.setProperty("libtorrent4j.jni.path", out.absolutePath)
                LibTorrent.version().isNotEmpty()
            }.getOrElse { e -> System.err.println("libtorrent unavailable: $e"); false }
        }
    }
}
