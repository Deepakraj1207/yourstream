package dev.yourstream.library

import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Debug output + scale check: prints a summary of a synthetic 10,000-file library. */
@RunWith(RobolectricTestRunner::class)
class LibraryScaleTest {

    private val env = LibraryTestEnv()
    @After fun tearDown() = env.close()

    @Test
    fun tenThousandFiles() = runTest {
        with(env) {
            val indexMs = measureTimeMillis {
                (1..2_000).forEach { file("Film %04d.%d.1080p.mkv".format(it, 1960 + it % 60)) }
                (1..200).forEach { s -> (1..40).forEach { e -> file("Show %03d.S%02dE%02d.Tamil.mkv".format(s, 1 + e / 20, e)) } }
            }
            var result: dev.yourstream.library.api.LibrarySyncResult
            val syncMs = measureTimeMillis { result = sync.sync() }
            val firstPage: List<dev.yourstream.library.api.LibraryItemSummary>
            val pageMs = measureTimeMillis {
                firstPage = media.search(LibraryQuery(sort = LibrarySort.RECENTLY_ADDED), pageSize = 50).asSnapshot { }
            }
            val searchMs = measureTimeMillis { media.search(LibraryQuery(text = "show 1")).all() }
            val incrementalMs = measureTimeMillis { file("Show 001.S03E01.Tamil.mkv"); sync.sync() }

            println("LIBRARY|indexed 10,000 files in ${indexMs}ms; first sync ${syncMs}ms: $result")
            println("LIBRARY|first page (${firstPage.size} rows) ${pageMs}ms; FTS search ${searchMs}ms; one new episode re-sync ${incrementalMs}ms")
            println("LIBRARY|movies=${media.count(LibraryQuery(kinds = setOf(LibraryKind.MOVIE))).first()} series=${media.count(LibraryQuery(kinds = setOf(LibraryKind.SERIES))).first()}")
            val show = media.search(LibraryQuery(text = "show 001")).all().first()
            println("LIBRARY|${show.title}: ${show.seasonCount} seasons, ${show.episodeCount} episodes; seasons=" +
                series.seasons(show.id).first().joinToString { "${it.displayName}(${it.episodeCount})" })

            assertEquals(10_000, result.filesProcessed)
            assertEquals(2_000, media.count(LibraryQuery(kinds = setOf(LibraryKind.MOVIE))).first())
            assertEquals(200, media.count(LibraryQuery(kinds = setOf(LibraryKind.SERIES))).first())
            assertEquals(150, firstPage.size, "Paging's initial load is 3 pages; the rest loads on scroll")
            val afterNewEpisode = media.search(LibraryQuery(sort = LibrarySort.RECENTLY_ADDED), pageSize = 50).asSnapshot { }
            assertEquals("Show 001", afterNewEpisode.first().title, "the new episode bumped its series to the top")
            assertTrue(syncMs < 120_000)
        }
    }
}
