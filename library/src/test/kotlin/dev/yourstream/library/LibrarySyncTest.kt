package dev.yourstream.library

import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.Season
import dev.yourstream.telegram.api.model.TelegramMediaContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {

    private val env = LibraryTestEnv()
    @After fun tearDown() = env.close()

    @Test
    fun filesBecomeMoviesSeriesEpisodesAndStandaloneVideos() = runTest {
        with(env) {
            val m1 = file("Movie.Name.2024.1080p.WEB-DL.mkv")
            val m2 = file("Movie.Name.2024.720p.HDRip.mkv")
            val d1 = file("Doraemon.S01E05.Tamil.720p.mkv")
            file("Doraemon.S01E06.Tamil.720p.mkv")
            file("Doraemon.S02E01.Tamil.720p.mkv")
            val s1 = file("Shinchan.125.Tamil.mp4")
            file("Shinchan.126.Tamil.mp4")
            val v = file("VID_20240301_WA0003.mp4", type = TelegramMediaContentType.VIDEO)
            file("Movie.Name.2024.en.srt", size = 50_000)
            file("Doraemon.S01E05.Tamil.srt", size = 40_000)

            val result = sync.sync()
            assertEquals(10, result.filesProcessed)

            val all = media.search(LibraryQuery(includeUntitled = true)).all()
            assertEquals(3, media.search(LibraryQuery()).all().size, "the untitled video without a poster is not browsed")
            // A→Z; the generic VID_ file is a standalone video titled from the chat ("Chat").
            assertEquals(
                listOf("Chat" to LibraryKind.VIDEO, "Doraemon" to LibraryKind.SERIES, "Movie Name" to LibraryKind.MOVIE, "Shinchan" to LibraryKind.SERIES),
                all.map { it.title to it.kind },
            )

            // One movie, two versions, best first.
            val movie = media.getItem(itemIdOf(m1))!!
            assertEquals(itemIdOf(m1), itemIdOf(m2))
            assertEquals(listOf(1080, 720), movie.versions.map { it.resolution })
            assertEquals(2024, movie.summary.year)

            // Series → seasons → episodes.
            val doraemon = itemIdOf(d1)
            val seasons = series.seasons(doraemon).first()
            assertEquals(listOf(1, 2), seasons.map { it.number })
            assertEquals(listOf(2, 1), seasons.map { it.episodeCount })
            val eps = episodes.episodes(doraemon).all()
            assertEquals(listOf("S01E05", "S01E06", "S02E01"), eps.map { it.label })
            assertEquals(3, media.getItem(doraemon)!!.summary.episodeCount)
            assertEquals(listOf("ta"), media.getItem(doraemon)!!.languages)

            // Season-less numbering.
            val shinchan = itemIdOf(s1)
            assertEquals(listOf(Season.ABSOLUTE), series.seasons(shinchan).first().map { it.number })
            assertEquals(listOf(125, 126), episodes.episodes(shinchan).all().map { it.number })

            // Unidentifiable file.
            assertEquals(LibraryKind.VIDEO, media.getItem(itemIdOf(v))!!.summary.kind)

            // Subtitle files attach to the movie and to the episode.
            assertEquals(listOf("en"), subtitles.subtitles(PlaybackTarget(itemIdOf(m1))).first().map { it.language })
            val episodeId = eps.first().id
            assertEquals(1, subtitles.subtitles(PlaybackTarget(doraemon, episodeId)).first().size)

            // Audio tracks guessed from the name.
            assertEquals(listOf("ta"), audio.tracks(mediaId(d1)).map { it.language })
        }
    }

    @Test
    fun forwardedCopiesAreVersionsOfTheSameItem() = runTest {
        with(env) {
            val original = file("Leo.2023.Tamil.1080p.mkv", chatId = 1, uniqueId = "same")
            val forward = file("Leo.2023.Tamil.1080p.mkv", chatId = 2, uniqueId = "same")
            sync.sync()
            assertEquals(itemIdOf(original, 1), itemIdOf(forward, 2))
            val versions = media.getItem(itemIdOf(original, 1))!!.versions
            assertEquals(listOf(false, true), versions.sortedBy { it.isDuplicate }.map { it.isDuplicate })
            assertEquals(1, media.count(LibraryQuery()).first())
        }
    }

    @Test
    fun syncIsIncremental() = runTest {
        with(env) {
            file("Movie.Name.2024.1080p.mkv")
            assertEquals(1, sync.sync().filesProcessed)
            assertEquals(0, sync.sync().filesProcessed, "nothing changed, nothing processed")

            file("Other.Movie.2020.mkv")
            val second = sync.sync()
            assertEquals(1, second.filesProcessed)
            assertEquals(1, second.itemsCreated)
        }
    }

    @Test
    fun aYearlessFileJoinsTheOnlyDatedMovieWithThatTitle() = runTest {
        with(env) {
            val dated = file("Interstellar.2014.1080p.mkv")
            sync.sync()
            val yearless = file("Interstellar.720p.HDRip.mkv")
            sync.sync()
            assertEquals(itemIdOf(dated), itemIdOf(yearless))
        }
    }

    @Test
    fun deletedFilesLeaveTheLibraryButUserDataStays() = runTest {
        with(env) {
            val gone = file("Throwaway.Movie.2019.mkv")
            val kept = file("Loved.Movie.2018.mkv")
            sync.sync()
            val lovedId = itemIdOf(kept)
            media.setFavorite(lovedId, true)
            watch.updateProgress(PlaybackTarget(lovedId), mediaId(kept), positionMs = 600_000, durationMs = 6_000_000)

            store.markDeleted(1, listOf(gone, kept))
            sync.sync()

            assertEquals(0, media.count(LibraryQuery()).first(), "nothing playable")
            assertNull(env.db.libraryDao().itemIdByKey("movie:throwaway movie:2019"), "no user data → removed")
            val loved = assertNotNull(media.getItem(lovedId), "favorite + progress → kept")
            assertFalse(loved.summary.isAvailable)
            assertEquals(1, media.count(LibraryQuery(includeUnavailable = true)).first())

            // The file comes back (re-scan): same item, progress intact.
            file("Loved.Movie.2018.mkv", messageId = kept)
            sync.sync()
            assertEquals(lovedId, itemIdOf(kept))
            assertTrue(media.getItem(lovedId)!!.summary.isAvailable)
            assertEquals(600_000, watch.progress(PlaybackTarget(lovedId)).first()!!.positionMs)
        }
    }

    @Test
    fun purgingAChatCleansTheLibraryThroughTriggers() = runTest {
        with(env) {
            file("Chat.Two.Movie.2020.mkv", chatId = 2)
            sync.sync()
            assertEquals(1, media.count(LibraryQuery()).first())
            env.db.indexedChatDao().delete(2) // cascades: media → media_link → dirty item
            sync.sync()
            assertEquals(0, media.count(LibraryQuery(includeUnavailable = true)).first())
        }
    }

    @Test
    fun reparsedMetadataMovesTheFile() = runTest {
        with(env) {
            val id = file("Some.Film.2021.mkv")
            sync.sync()
            val before = itemIdOf(id)
            val dao = env.db.mediaDao()
            val parsed = dao.getWithParsed(mediaId(id))!!.parsed!!
            dao.upsertParsed(parsed.copy(title = "Better Title", normalizedTitle = "better title"))
            sync.sync()
            val after = media.getItem(itemIdOf(id))!!
            assertEquals("Better Title", after.summary.title)
            assertNull(media.getItem(before), "old item had no user data → removed")
        }
    }
}
