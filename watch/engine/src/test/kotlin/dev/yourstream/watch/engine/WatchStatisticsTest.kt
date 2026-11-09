package dev.yourstream.watch.engine

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.data.RoomWatchHistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WatchStatisticsTest {
    private val min = 60_000L
    private var now = java.time.LocalDate.of(2026, 11, 9).atTime(19, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val clock = Clock { now }
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val f = WatchFixture(db)
    private val history = RoomWatchHistoryRepository(db, clock)
    private val stats = RoomWatchStatistics(db, clock).apply { zone = { ZoneOffset.UTC } }

    @After fun close() = db.close()

    @Test
    fun statisticsComeFromTheLocalWatchHistory() = runBlocking {
        f.transaction {
            item(1, "Vikram", year = 2022); file(1, "Vikram.2022.mkv"); language(1, "ta"); metadata(1, "Vikram", genres = listOf("action"))
            item(2, "Jailer", year = 2023); file(2, "Jailer.2023.mkv"); language(2, "ta")
        }
        assertTrue(stats.stats().first().isEmpty)

        // One sitting of Vikram: progress saved every ~10 minutes until it is finished.
        val film = 150 * min
        for (p in listOf(10, 20, 150)) { now += 10 * min; history.updateProgress(PlaybackTarget(1), null, p * min, film) }
        // Jailer the next day, 30 minutes.
        now += 24 * 60 * min
        for (p in listOf(1, 31)) { history.updateProgress(PlaybackTarget(2), null, p * min, film); now += 30 * min }

        val s = stats.stats().first()
        assertEquals(2, s.sessions)
        assertEquals(1, s.moviesFinished)
        assertEquals(2, s.titlesWatched)
        assertEquals(2, s.currentStreakDays)
        assertEquals((20 + 30) * min, s.totalMs, "wall time between the first and last save of each sitting")
        assertEquals("ta", s.topLanguages.single().key)
    }
}
