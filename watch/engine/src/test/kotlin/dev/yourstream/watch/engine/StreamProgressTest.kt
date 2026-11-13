package dev.yourstream.watch.engine

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Resume points for add-on streams, on the real database. */
@RunWith(RobolectricTestRunner::class)
class StreamProgressTest {
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private var now = 1_000L
    private val store = StreamProgressStore(db, object : Clock { override fun nowMillis() = now })
    private val hour = 3_600_000L

    @After fun tearDown() = db.close()

    @Test
    fun resumesWhereTheLastStreamStoppedAndStartsOverOnceFinished() = runTest {
        val key = StreamProgressStore.keyOf("movie", "tt1", null, null, null)
        assertNull(store.resumeAt(key), "never played")

        store.save(key, "Vikram", null, 20_000, 2 * hour)
        assertNull(store.resumeAt(key), "a 20 s peek is not a resume point")

        store.save(key, "Vikram", null, 40 * 60_000, 2 * hour)
        assertEquals(40 * 60_000L, store.resumeAt(key))

        // The credits: finished, so the next play starts from the beginning.
        store.save(key, "Vikram", null, 2 * hour - 60_000, 2 * hour)
        assertNull(store.resumeAt(key))
    }

    @Test
    fun startOverForgetsThePosition() = runTest {
        val key = "movie:tt2"
        store.save(key, "Leo", null, 30 * 60_000, 2 * hour)
        store.clear(key)
        assertNull(store.resumeAt(key))
    }

    @Test
    fun theSameEpisodeIsOneKeyAcrossAddOns() {
        // Two add-ons with their own ids for the same series, both knowing the IMDb id.
        assertEquals(
            StreamProgressStore.keyOf("SERIES", "kitsu:1", "tt0141842", 1, 2),
            StreamProgressStore.keyOf("series", "tmdb:1398", "tt0141842", 1, 2),
        )
        assertEquals("series:tt0141842:S01E02", StreamProgressStore.keyOf("series", "x", "tt0141842", 1, 2))
        assertNotEquals(StreamProgressStore.keyOf("series", "x", "tt0141842", 1, 2), StreamProgressStore.keyOf("series", "x", "tt0141842", 1, 3))
        assertEquals("movie:kitsu:7", StreamProgressStore.keyOf("movie", "kitsu:7", null, null, null), "no IMDb id: the add-on's id")
    }

    @Test
    fun onlyTheNewest500AreKept() = runTest {
        repeat(505) { i ->
            now += 1
            store.save("movie:m$i", "M$i", null, 10 * 60_000, 2 * hour)
        }
        val count = db.query("SELECT COUNT(*) FROM stream_progress", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(500, count)
        assertNull(store.resumeAt("movie:m0"), "the oldest went first")
        assertEquals(10 * 60_000L, store.resumeAt("movie:m504"))
    }
}
