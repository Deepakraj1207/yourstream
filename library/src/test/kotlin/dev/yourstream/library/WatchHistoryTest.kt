package dev.yourstream.library

import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.data.CompletionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WatchHistoryTest {

    private val env = LibraryTestEnv()
    @After fun tearDown() = env.close()

    @Test
    fun completionPolicy() {
        assertFalse(CompletionPolicy.isComplete(0, 0))
        assertTrue(CompletionPolicy.isComplete(90, 100))
        assertFalse(CompletionPolicy.isComplete(80, 100))
        // 2 h film: the last 3 minutes are credits.
        assertTrue(CompletionPolicy.isComplete(7_200_000 - 170_000, 7_200_000))
        // 10 min clip: 3 minutes left is not "done".
        assertFalse(CompletionPolicy.isComplete(420_000, 600_000))
    }

    @Test
    fun progressResumeAndCompletion() = runTest {
        with(env) {
            val f = file("Movie.Name.2024.mkv"); sync.sync()
            val target = PlaybackTarget(itemIdOf(f))
            val mid = mediaId(f)

            assertEquals(0, watch.updateProgress(target, mid, 10_000, 6_000_000).positionMs, "a 10 s peek is not a resume point")
            assertEquals(1_200_000, watch.updateProgress(target, mid, 1_200_000, 6_000_000).positionMs)
            assertEquals(listOf(target), watch.continueWatching().first().map { it.target })

            val done = watch.updateProgress(target, mid, 5_900_000, 6_000_000)
            assertTrue(done.completed)
            assertEquals(0, done.positionMs, "finished → next play starts from the beginning")
            assertTrue(watch.continueWatching().first().isEmpty())
            assertEquals(1, env.db.watchDao().state(target.itemId, 0)!!.playCount)

            // Rewatching starts a new in-progress state, keeping the play count.
            watch.updateProgress(target, mid, 600_000, 6_000_000)
            val again = env.db.watchDao().state(target.itemId, 0)!!
            assertFalse(again.completed)
            assertEquals(1, again.playCount)
        }
    }

    @Test
    fun continueWatchingShowsOneEntryPerSeriesNewestFirst() = runTest {
        with(env) {
            val e1 = file("Doraemon.S01E01.mkv"); val e2 = file("Doraemon.S01E02.mkv"); val m = file("Movie.Name.2024.mkv")
            sync.sync()
            val series = itemIdOf(e1)
            val eps = episodes.episodes(series).all()
            watch.updateProgress(PlaybackTarget(series, eps[0].id), mediaId(e1), 300_000, 1_300_000); clock.now += 1000
            watch.updateProgress(PlaybackTarget(itemIdOf(m)), mediaId(m), 900_000, 7_000_000); clock.now += 1000
            watch.updateProgress(PlaybackTarget(series, eps[1].id), mediaId(e2), 200_000, 1_300_000)

            val entries = watch.continueWatching().first()
            assertEquals(listOf(eps[1].id, null), entries.map { it.target.episodeId })
            assertEquals(2, entries.first().episodeNumber)
            assertEquals(listOf("Doraemon", "Movie Name"), watch.recentlyWatched().all().distinctBy { it.title }.map { it.title })
        }
    }

    @Test
    fun markingSeriesAndSeasonsWatched() = runTest {
        with(env) {
            listOf("S01E01", "S01E02", "S02E01").forEach { file("Show.$it.mkv") }
            sync.sync()
            val show = media.search(LibraryQuery(text = "show")).all().single()
            val seasons = series.seasons(show.id).first()

            watch.markSeasonWatched(seasons[0].id, true)
            assertEquals(2, media.getItem(show.id)!!.summary.watchedEpisodeCount)
            assertEquals(listOf(true, true, false), episodes.episodes(show.id).all().map { it.progress?.completed == true })

            watch.markWatched(PlaybackTarget(show.id), true)
            assertEquals(3, media.getItem(show.id)!!.summary.watchedEpisodeCount)
            watch.markWatched(PlaybackTarget(show.id), false)
            assertEquals(0, media.getItem(show.id)!!.summary.watchedEpisodeCount)
            assertNull(media.getItem(show.id)!!.summary.lastWatchedAt)

            val e1 = episodes.episodes(show.id).all().first()
            assertEquals("S01E02", episodes.nextEpisode(e1.id)!!.label)
        }
    }

    @Test
    fun historyGroupsUpdatesIntoSessions() = runTest {
        with(env) {
            val f = file("Movie.Name.2024.mkv"); sync.sync()
            val t = PlaybackTarget(itemIdOf(f))
            watch.updateProgress(t, mediaId(f), 60_000, 6_000_000)
            clock.now += 60_000
            watch.updateProgress(t, mediaId(f), 120_000, 6_000_000)
            assertEquals(1, watch.history().all().size, "same sitting")
            clock.now += 31 * 60_000
            watch.updateProgress(t, mediaId(f), 700_000, 6_000_000)
            val history = watch.history().all()
            assertEquals(2, history.size)
            assertEquals(700_000, history.first().positionMs)

            watch.clearProgress(t)
            assertTrue(watch.history().all().isEmpty())
            assertNull(watch.progress(t).first())
        }
    }
}
