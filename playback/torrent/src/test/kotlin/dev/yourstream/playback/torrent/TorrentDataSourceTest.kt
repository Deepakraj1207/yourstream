package dev.yourstream.playback.torrent

import androidx.media3.common.C
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TorrentDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A file that "downloads": bytes up to [available] are readable; reads past it wait. */
    private class GrowingSource(override val file: File, override val size: Long) : PieceSource {
        override val key = "aabbccddeeff00112233445566778899aabbccdd"
        val available = AtomicLong(0)
        @Volatile var failure: IOException? = null
        val waits = AtomicLong(0)

        override fun awaitReadable(position: Long): Long {
            while (available.get() <= position) {
                failure?.let { throw it }
                waits.incrementAndGet()
                Thread.sleep(5)
            }
            return available.get() - position
        }
    }

    private val data = ByteArray(100_000) { (it * 31 % 251).toByte() }

    private fun source(): GrowingSource {
        val f = tmp.newFile("film.mkv").apply { writeBytes(data) }
        return GrowingSource(f, data.size.toLong())
    }

    private fun spec(position: Long = 0) = DataSpec.Builder().setUri(TorrentUri.of("AABBCCDDEEFF00112233445566778899AABBCCDD", "film.mkv")).setPosition(position).build()

    @Test
    fun readsWhatHasArrivedAndWaitsForTheRest() {
        val s = source()
        val ds = TorrentDataSource { if (it == s.key) s else null }
        assertEquals(100_000L - 1_000, ds.open(spec(1_000)))
        s.available.set(5_000)
        val buf = ByteArray(64_000)
        val first = ds.read(buf, 0, buf.size)
        assertEquals(4_000, first, "only what has arrived")
        assertContentEquals(data.copyOfRange(1_000, 5_000), buf.copyOf(first))

        val later = thread { Thread.sleep(50); s.available.set(100_000) }
        var total = first
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            assertContentEquals(data.copyOfRange(1_000 + total, 1_000 + total + n), buf.copyOf(n))
            total += n
        }
        later.join()
        assertEquals(99_000, total)
        assertTrue(s.waits.get() > 0, "the reader waited for the missing bytes")
        ds.close()
    }

    @Test
    fun cancellingALoadEndsTheWait() {
        val s = source()
        val ds = TorrentDataSource { s }
        ds.open(spec())
        var error: Throwable? = null
        val reader = thread { error = runCatching { ds.read(ByteArray(10), 0, 10) }.exceptionOrNull() }
        Thread.sleep(50)
        reader.interrupt()
        reader.join(2_000)
        assertTrue(error is InterruptedIOException, "got $error")
    }

    @Test
    fun peerTroubleBecomesAPlaybackError() {
        val s = source()
        s.failure = TorrentException("No peer sent any data for 120 seconds.")
        val ds = TorrentDataSource { s }
        ds.open(spec())
        val e = assertFailsWith<DataSourceException> { ds.read(ByteArray(10), 0, 10) }
        assertTrue(e.cause is TorrentException)
    }

    @Test
    fun closedStreamsCannotBeOpened() {
        val ds = TorrentDataSource { null }
        assertFailsWith<DataSourceException> { ds.open(spec()) }
        assertEquals("aabbccddeeff00112233445566778899aabbccdd", TorrentUri.key(TorrentUri.of("AABBCCDDEEFF00112233445566778899AABBCCDD", "a b.mkv")))
    }
}
