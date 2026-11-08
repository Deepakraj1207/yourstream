package dev.yourstream.watch.engine

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.data.RoomWatchHistoryRepository
import dev.yourstream.watch.core.AbandonReason
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.core.NextReason
import dev.yourstream.watch.core.WatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WatchIntelligenceTest {
    private val day = 24 * 3600 * 1000L
    private var now = 1_000 * day
    private val clock = Clock { now }
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val f = WatchFixture(db)
    private val history = RoomWatchHistoryRepository(db, clock)
    private val watch = RoomWatchIntelligence(db, clock)

    private val ep = 22 * 60_000L
    private val film = 150 * 60_000L

    // Ids
    private val ben10 = 1L
    private val doraemon = 2L
    private val vikram = 3L
    private val jailer = 4L
    private val leo = 5L
    private val motu = 6L
    private val hiddenMovie = 7L
    private val ben = mutableMapOf<String, Long>()
    private val dora = mutableMapOf<String, Long>()
    private var doraSeason = 0L

    @Before fun library() = f.transaction {
        item(ben10, "Ben 10", "SERIES", episodes = 12, addedAt = 10)
        season(ben10, 1).let { s -> (1..10).forEach { e -> ben["S01E%02d".format(e)] = episode(ben10, s, 1, e) } }
        season(ben10, 2).let { s -> (1..2).forEach { e -> ben["S02E%02d".format(e)] = episode(ben10, s, 2, e) } }
        file(ben10, "Ben10.S01E01.mkv")
        item(doraemon, "Doraemon", "SERIES", episodes = 3, addedAt = 20)
        doraSeason = season(doraemon, 1)
        doraSeason.let { s -> (1..3).forEach { e -> dora["S01E%02d".format(e)] = episode(doraemon, s, 1, e) } }
        file(doraemon, "Doraemon.S01E01.mkv")
        item(vikram, "Vikram", year = 2022, addedAt = 30); file(vikram, "Vikram.2022.mkv")
        item(jailer, "Jailer", year = 2023, addedAt = 40); file(jailer, "Jailer.2023.mkv")
        item(leo, "Leo", year = 2023, addedAt = 50); file(leo, "Leo.2023.mkv")
        item(motu, "Motu Patlu", "SERIES", episodes = 5, addedAt = 60)
        season(motu, 1).let { s -> (1..5).forEach { e -> episode(motu, s, 1, e) } }
        file(motu, "Motu.Patlu.E01.mp4")
        item(hiddenMovie, "Hidden", hidden = true, addedAt = 70); file(hiddenMovie, "Hidden.mkv")
    }

    @After fun close() = db.close()

    private fun play(target: PlaybackTarget, percent: Int, duration: Long = ep, minutesLater: Long = 30) = runBlocking {
        now += minutesLater * 60_000
        history.updateProgress(target, null, duration * percent / 100, duration)
    }
    private fun ben(label: String) = PlaybackTarget(ben10, ben.getValue(label))
    private fun cw() = runBlocking { watch.continueNow() }

    @Test
    fun specExampleS01E06ThenS01E07() {
        for (e in 1..5) play(ben("S01E%02d".format(e)), 100)
        play(ben("S01E06"), 35)
        cw().single().let {
            assertEquals(ben("S01E06"), it.target, "S01E05 = 100 %, S01E06 = 35 % → continue S01E06")
            assertEquals(NextAction.RESUME, it.action)
            assertEquals(35, it.percent)
            assertEquals(ep * 35 / 100, it.positionMs)
        }
        play(ben("S01E06"), 100)
        cw().single().let {
            assertEquals(ben("S01E07"), it.target, "S01E06 = 100 % → S01E07")
            assertEquals(NextAction.START, it.action)
            assertEquals(NextReason.NEXT_AFTER_COMPLETED, it.reason)
            assertEquals(0, it.positionMs)
            assertEquals(50, it.seriesPercent, "6 of 12 episodes")
        }
        assertEquals(ben.getValue("S01E07"), runBlocking { watch.nextEpisode(ben10) }.episode?.episodeId)
    }

    @Test
    fun continueWatchingMixesResumesAndNextEpisodesNewestFirst() {
        play(PlaybackTarget(vikram), 40, film)                // movie in progress (oldest)
        play(PlaybackTarget(jailer), 100, film)               // finished: not listed
        play(PlaybackTarget(doraemon, dora.getValue("S01E01")), 100) // series: next is E02
        play(ben("S01E01"), 60)                                // newest: resume
        val list = cw()
        assertEquals(listOf(ben("S01E01"), PlaybackTarget(doraemon, dora.getValue("S01E02")), PlaybackTarget(vikram)), list.map { it.target })
        assertEquals(listOf(NextAction.RESUME, NextAction.START, NextAction.RESUME), list.map { it.action })
        // A short peek (< 30 s) is not a resume point and does not list a movie.
        runBlocking { history.updateProgress(PlaybackTarget(leo), null, 10_000, film) }
        assertTrue(cw().none { it.target.itemId == leo })
        // Finishing a series removes it.
        play(PlaybackTarget(doraemon, dora.getValue("S01E02")), 100); play(PlaybackTarget(doraemon, dora.getValue("S01E03")), 100)
        assertTrue(cw().none { it.target.itemId == doraemon })
        assertEquals(WatchStatus.COMPLETED, runBlocking { watch.summary(doraemon) }!!.status)
        // Hidden items never show.
        play(PlaybackTarget(hiddenMovie), 50, film)
        assertTrue(cw().none { it.target.itemId == hiddenMovie })
    }

    @Test
    fun newEpisodeAfterCatchingUpComesBackToTheTop() {
        for (e in 1..3) play(PlaybackTarget(doraemon, dora.getValue("S01E%02d".format(e))), 100)
        play(PlaybackTarget(vikram), 40, film)
        assertEquals(listOf(vikram), cw().map { it.target.itemId })
        // A new Doraemon episode arrives later.
        now += day
        var e4 = 0L
        f.transaction {
            e4 = episode(doraemon, doraSeason, 1, 4, addedAt = now)
            sql.execSQL("UPDATE library_item SET latest_added_at = ? WHERE id = ?", arrayOf<Any?>(now, doraemon))
        }
        val list = cw()
        assertEquals(PlaybackTarget(doraemon, e4), list.first().target)
        assertEquals(NextReason.NEW_EPISODE, list.first().reason)
        val added = runBlocking { watch.recentlyAddedNow() }
        assertEquals(doraemon, added.first().itemId)
        assertEquals(1, added.first().newEpisodes)
    }

    @Test
    fun dismissAndAbandon() {
        play(PlaybackTarget(vikram), 40, film)
        play(ben("S01E01"), 100)
        play(PlaybackTarget(leo), 5, film) // 7.5 minutes of a 150 minute film
        runBlocking { watch.dismiss(vikram) }
        assertEquals(setOf(ben10, leo), cw().map { it.target.itemId }.toSet())
        assertEquals(AbandonReason.DISMISSED, runBlocking { watch.abandonedNow() }.single().abandoned)
        // Playing it again brings it back.
        play(PlaybackTarget(vikram), 45, film)
        assertTrue(cw().any { it.target.itemId == vikram })

        // Two weeks idle: Leo (5 %) and Ben 10 (1 of 12 episodes) were dropped early; Vikram (45 %) is still "continue".
        now += 15 * day
        assertEquals(listOf(vikram), cw().map { it.target.itemId })
        val abandoned = runBlocking { watch.abandonedNow() }.associate { it.itemId to it.abandoned }
        assertEquals(mapOf(leo to AbandonReason.DROPPED_EARLY, ben10 to AbandonReason.DROPPED_EARLY), abandoned)
        // A month idle: Vikram too.
        now += 16 * day
        assertTrue(cw().isEmpty())
        assertEquals(AbandonReason.INACTIVE, runBlocking { watch.summary(vikram) }!!.abandoned)
        assertEquals(WatchStatus.ABANDONED, runBlocking { watch.summary(vikram) }!!.status)
        runBlocking { watch.restore(vikram) }
        assertEquals(AbandonReason.INACTIVE, runBlocking { watch.summary(vikram) }!!.abandoned, "restore only clears the dismissal")
    }

    @Test
    fun summaryTracksEverything() {
        play(PlaybackTarget(vikram), 100, film)
        // Second viewing: the player reports progress along the way, then the end.
        play(PlaybackTarget(vikram), 30, film, minutesLater = 300)
        play(PlaybackTarget(vikram), 100, film, minutesLater = 20)
        play(PlaybackTarget(vikram), 20, film, minutesLater = 300)  // third, in progress
        f.sql.execSQL("INSERT INTO favorite VALUES (?, ?)", arrayOf<Any?>(vikram, now))
        runBlocking { watch.summary(vikram) }!!.let {
            assertEquals(WatchStatus.IN_PROGRESS, it.status)
            assertEquals(20, it.percent)
            assertEquals(film * 20 / 100, it.positionMs)
            assertEquals(now, it.lastWatchedAt)
            assertEquals(2, it.watchCount, "finished twice")
            assertEquals(3, it.sessions, "three sittings (> 30 min apart)")
            assertTrue(it.favorite)
        }
        play(ben("S01E01"), 100); play(ben("S01E02"), 100); play(ben("S01E03"), 50)
        runBlocking { watch.summary(ben10) }!!.let {
            assertEquals(2, it.completedEpisodes)
            assertEquals(1, it.partialEpisodes)
            assertEquals(12, it.totalEpisodes)
            assertEquals((200 + 50) / 12, it.percent)
            assertEquals(ben.getValue("S01E03"), it.next?.episode?.episodeId)
            assertEquals(NextAction.RESUME, it.next?.action)
        }
        val eps = runBlocking { watch.episodes(ben10) }
        assertEquals(listOf(100, 100, 50, 0), eps.take(4).map { it.percent })
        assertEquals("S02E01", eps[10].label)
    }

    @Test
    fun recentlyWatchedRecentlyAddedAndFavorites() {
        play(ben("S01E01"), 100)
        play(PlaybackTarget(vikram), 30, film)
        play(ben("S01E02"), 40)
        val recent = runBlocking { watch.recentlyWatchedNow() }
        assertEquals(listOf(ben10, vikram), recent.map { it.summary.itemId }, "one row per item, newest first")
        assertEquals("S01E02", recent.first().lastEpisode?.label)

        val added = runBlocking { watch.recentlyAddedNow() }
        assertEquals(listOf(motu, leo, jailer, vikram, doraemon, ben10), added.map { it.itemId }, "newest first, hidden excluded")
        assertEquals(listOf(motu, doraemon, ben10), runBlocking { watch.recentlyAddedNow(kinds = setOf(LibraryKind.SERIES)) }.map { it.itemId })

        f.sql.execSQL("INSERT INTO favorite VALUES (?, ?)", arrayOf<Any?>(motu, 1))
        f.sql.execSQL("INSERT INTO favorite VALUES (?, ?)", arrayOf<Any?>(ben10, 2))
        runBlocking { watch.favoritesNow() }.let { favs ->
            assertEquals(listOf(ben10, motu), favs.map { it.itemId })
            assertEquals(WatchStatus.UNWATCHED, favs.last().status)
            assertEquals(NextReason.NOT_STARTED, favs.last().next?.reason)
            assertNull(favs.last().lastWatchedAt)
        }
    }

    @Test
    fun listsAreLive() = runBlocking {
        assertTrue(watch.continueWatching().first().isEmpty())
        play(PlaybackTarget(jailer), 50, film)
        assertEquals(listOf(jailer), watch.continueWatching().first().map { it.target.itemId })
        history.markWatched(PlaybackTarget(jailer), true)
        assertTrue(watch.continueWatching().first().isEmpty())
        assertEquals(1, watch.recentlyWatched().first().size)
    }
}
