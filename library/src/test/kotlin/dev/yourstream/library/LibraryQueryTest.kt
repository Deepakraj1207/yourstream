package dev.yourstream.library

import androidx.paging.PagingSource
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.library.data.LibraryQueryBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LibraryQueryTest {

    private val env = LibraryTestEnv()
    @After fun tearDown() = env.close()

    private suspend fun titles(q: LibraryQuery) = with(env) { media.search(q).all().map { it.title } }

    private suspend fun seed() = with(env) {
        file("The.Matrix.1999.1080p.mkv")
        file("Avatar.2009.2160p.mkv")
        file("Vikram.2022.Tamil.720p.mkv")
        file("Leo.2023.Tamil.1080p.mkv")
        file("Doraemon.S01E01.Tamil.mkv")
        file("Doraemon.S01E02.Tamil.mkv")
        file("Breaking.Bad.S01E01.1080p.mkv")
        sync.sync()
    }

    @Test
    fun sortsIgnoreArticlesAndBreakTiesStably() = runTest {
        seed()
        assertEquals(listOf("Avatar", "Breaking Bad", "Doraemon", "Leo", "The Matrix", "Vikram"), titles(LibraryQuery()))
        assertEquals(listOf("Vikram", "The Matrix", "Leo", "Doraemon", "Breaking Bad", "Avatar"), titles(LibraryQuery(reversed = true)))
        assertEquals(listOf("Leo", "Vikram", "Avatar", "The Matrix"), titles(LibraryQuery(kinds = setOf(LibraryKind.MOVIE), sort = LibrarySort.YEAR)))
        // Recently added: the newest file first (Breaking Bad was indexed last).
        assertEquals("Breaking Bad", titles(LibraryQuery(sort = LibrarySort.RECENTLY_ADDED)).first())
    }

    @Test
    fun filtersCombine() = runTest {
        seed()
        assertEquals(listOf("Doraemon", "Leo", "Vikram"), titles(LibraryQuery(language = "ta")))
        assertEquals(listOf("Leo", "Vikram"), titles(LibraryQuery(language = "ta", kinds = setOf(LibraryKind.MOVIE))))
        assertEquals(listOf("Avatar"), titles(LibraryQuery(minResolution = 2160)))
        assertEquals(listOf("Leo", "Vikram"), titles(LibraryQuery(yearFrom = 2020)))
        assertEquals(listOf("The Matrix"), titles(LibraryQuery(yearTo = 2000)))
        assertEquals(listOf("Breaking Bad", "Doraemon"), titles(LibraryQuery(kinds = setOf(LibraryKind.SERIES))))
    }

    @Test
    fun fullTextSearchUsesTitlesKeysAndLanguages() = runTest {
        seed()
        assertEquals(listOf("Doraemon"), titles(LibraryQuery(text = "dorae")))
        assertEquals(listOf("The Matrix"), titles(LibraryQuery(text = "matrix")))
        assertEquals(listOf("Doraemon", "Leo", "Vikram"), titles(LibraryQuery(text = "tamil")))
        assertEquals(listOf("Leo"), titles(LibraryQuery(text = "tamil 2023")))
        assertTrue(titles(LibraryQuery(text = "\" OR 1=1 --")).isEmpty(), "operators/quotes are neutralised")
        assertEquals(3, env.media.count(LibraryQuery(text = "tamil")).first())
    }

    @Test
    fun favoritesAndWatchStatusFilters() = runTest {
        seed()
        with(env) {
            val matrix = media.search(LibraryQuery(text = "matrix")).all().single().id
            val avatar = media.search(LibraryQuery(text = "avatar")).all().single().id
            val doraemon = media.search(LibraryQuery(text = "doraemon")).all().single().id
            media.setFavorite(avatar, true)
            clock.now += 1_000
            media.setFavorite(matrix, true)
            watch.updateProgress(PlaybackTarget(matrix), null, 3_000_000, 8_000_000) // in progress
            watch.markWatched(PlaybackTarget(avatar), true)
            val firstEpisode = episodes.episodes(doraemon).all().first().id
            watch.markWatched(PlaybackTarget(doraemon, firstEpisode), true) // series half watched

            assertEquals(listOf("The Matrix", "Avatar"), media.favorites().all().map { it.title }, "newest favorite first")
            assertEquals(listOf("Avatar", "The Matrix"), titles(LibraryQuery(favoritesOnly = true)))
            assertEquals(listOf("Avatar"), titles(LibraryQuery(watch = WatchFilter.WATCHED)))
            assertEquals(listOf("Doraemon", "The Matrix"), titles(LibraryQuery(watch = WatchFilter.IN_PROGRESS)))
            assertEquals(listOf("Breaking Bad", "Leo", "Vikram"), titles(LibraryQuery(watch = WatchFilter.UNWATCHED)))

            watch.markWatched(PlaybackTarget(doraemon), true) // whole series
            assertTrue("Doraemon" in titles(LibraryQuery(watch = WatchFilter.WATCHED)))
            val summary = media.search(LibraryQuery(text = "doraemon")).all().single()
            assertEquals(0, summary.unwatchedEpisodeCount)
            // Most recent activity first; never-watched items last.
            val byWatch = titles(LibraryQuery(sort = LibrarySort.RECENTLY_WATCHED))
            assertEquals("Doraemon", byWatch.first())
            assertEquals(setOf("Breaking Bad", "Leo", "Vikram"), byWatch.takeLast(3).toSet())
        }
    }

    @Test
    fun largeListsArePagedWithoutGapsOrRepeats() = runTest {
        with(env) {
            // Many items sharing years → lots of ties the id tie-breaker must order.
            (1..260).forEach { file("Movie %03d.%d.720p.mkv".format(it, 1950 + it % 7)) }
            sync.sync()
            for (sort in LibrarySort.entries) {
                // "Movie 001" is a generic name: untitled items, asked for explicitly here (paging is what is tested).
                val source = env.db.libraryDao().itemsPaged(LibraryQueryBuilder.list(LibraryQuery(sort = sort, includeUntitled = true)).query())
                val seen = mutableListOf<Long>()
                var key: Int? = null
                var pages = 0
                do {
                    val params: PagingSource.LoadParams<Int> = if (key == null) PagingSource.LoadParams.Refresh(null, 25, false) else PagingSource.LoadParams.Append(key, 25, false)
                    val page = source.load(params) as PagingSource.LoadResult.Page
                    seen += page.data.map { it.id }
                    key = page.nextKey
                    pages++
                } while (key != null)
                assertEquals(260, seen.size, "$sort: every row once")
                assertEquals(260, seen.toSet().size, "$sort: no repeats across page boundaries")
                assertTrue(pages >= 10, "$sort: really paged ($pages pages)")
            }
            assertEquals(260, media.count(LibraryQuery(includeUntitled = true)).first())
            assertEquals(0, media.count(LibraryQuery()).first(), "untitled and without posters: not browsed")
        }
    }

    @Test
    fun queryBuilderOnlyBindsUserInput() {
        val sql = LibraryQueryBuilder.list(LibraryQuery(text = "x'); DROP TABLE media;--", language = "ta'--", yearFrom = 2000))
        assertTrue("DROP" !in sql.sql)
        assertTrue("ta'--" in sql.args)
        assertEquals("x* drop* table* media*", LibraryQueryBuilder.ftsMatch("x'); DROP TABLE media;--"))
        assertNull(LibraryQueryBuilder.ftsMatch(" *** "))
        assertEquals("matrix", LibraryQueryBuilder.sortTitle("The Matrix"))
        assertEquals("the", LibraryQueryBuilder.sortTitle("The"))
    }
}
