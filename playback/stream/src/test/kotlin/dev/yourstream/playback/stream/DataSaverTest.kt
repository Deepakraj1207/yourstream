package dev.yourstream.playback.stream

import dev.yourstream.core.common.AppResult
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Data saver: when it is on, which version it prefers, how far ahead it streams, how much it keeps. */
class DataSaverTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mb = 1024L * 1024

    @After fun reset() { DataSaver.mode = DataSaver.Mode.OFF; DataSaver.metered = { false } }

    @Test
    fun onAlwaysOrOnMobileData() {
        assertFalse(DataSaver.active)
        DataSaver.mode = DataSaver.Mode.ALWAYS
        assertTrue(DataSaver.active)
        DataSaver.mode = DataSaver.Mode.MOBILE_DATA
        assertFalse(DataSaver.active, "Wi-Fi")
        DataSaver.metered = { true }
        assertTrue(DataSaver.active, "mobile data")
        DataSaver.metered = { error("no connectivity service") }
        assertFalse(DataSaver.active, "unknown counts as not metered")
    }

    private data class V(val name: String, val height: Int?, val size: Long, val local: Boolean = false)

    @Test
    fun smallestSuitableVersionFirst() {
        val versions = listOf(
            V("4K remux", 2160, 20_000 * mb), V("1080p x264", 1080, 2_000 * mb), V("720p x265", 720, 800 * mb),
            V("360p sample", 360, 50 * mb), V("unknown size", 1080, 0), V("no height", null, 1_200 * mb),
        )
        fun order(vs: List<V>) = DataSaver.smallestFirst(vs, { it.local }, { it.height }, { it.size }).map { it.name }
        assertEquals(listOf("720p x265", "no height", "1080p x264", "4K remux", "unknown size", "360p sample"), order(versions),
            "smallest of at least 480p; unknown sizes, then tiny versions last")
        assertEquals("4K remux", order(versions.map { if (it.height == 2160) it.copy(local = true) else it }).first(), "already on the device: no data at all")
    }

    @Test
    fun streamsLessAhead() {
        val config = StreamingConfig()
        val hour = StreamHints(sizeBytes = 3_600 * mb, durationMs = 3_600_000) // 1 MB/s
        assertEquals(90 * mb, config.readAheadFor(hour, saver = false))
        assertEquals(45 * mb, config.readAheadFor(hour, saver = true))
        assertEquals(32 * mb, config.readAheadFor(StreamHints(), saver = false))
        assertEquals(12 * mb, config.readAheadFor(StreamHints(), saver = true), "bitrate unknown")
        val tiny = StreamHints(sizeBytes = 180 * mb, durationMs = 3_600_000) // 50 KB/s
        assertEquals(4 * mb, config.readAheadFor(tiny, saver = true), "never below a few MB")
    }

    private fun streamed(id: Int, bytes: Long) = TelegramFile(id, "u$id", "r$id", bytes, bytes, "/tmp/$id", true, false, false, 0, bytes, bytes)

    @Test
    fun keepsLessOfWhatWasStreamed(): Unit = runBlocking {
        val sim = SimulatedTelegramFiles(tmp.newFolder())
        val deleted = mutableListOf<Int>()
        val repo = object : TelegramFileRepository by sim {
            override suspend fun deleteLocalCopy(fileId: Int): AppResult<Unit> { deleted += fileId; return AppResult.Ok(Unit) }
        }
        var now = 0L
        // Normal: 2 GB kept, three 300 MB streams stay.
        val normal = StreamCache(repo, clock = { now++ })
        for (id in 1..3) normal.recordAndTrim(streamed(id, 300 * mb), wasCompleteBefore = false, activeFileIds = emptySet())
        assertTrue(deleted.isEmpty())
        // Data saver: 500 MB kept; the oldest goes once a second stream is recorded (the newest is always kept).
        DataSaver.mode = DataSaver.Mode.ALWAYS
        val saver = StreamCache(repo, clock = { now++ })
        saver.recordAndTrim(streamed(11, 300 * mb), false, emptySet())
        assertTrue(deleted.isEmpty())
        saver.recordAndTrim(streamed(12, 300 * mb), false, emptySet())
        assertEquals(listOf(11), deleted)
        sim.close()
    }
}
