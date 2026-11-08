package dev.yourstream.search.engine

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchFilters
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.SoftFilter
import dev.yourstream.search.core.WatchState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LibrarySearchEngineTest {
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val indexer = SearchIndexer(db)
    private val engine = LibrarySearchEngine(db, indexer)
    private val f = SearchFixture(db)
    private var ben10S2E5 = 0L

    @Before fun library() = f.transaction {
        chat(1, "Kids Cartoon Channel")
        chat(2, "Tamil Movies HD")
        item(1, "Doraemon", "SERIES", episodes = 10); metadata(1, "Doraemon", listOf("Animation", "Comedy", "Family"), originalLanguage = "ja", rating = 7.5)
        language(1, "ta", "hi"); season(1, 1); file(1, "Doraemon.S01E01.Tamil.Hindi.480p.WEB-DL.mkv", chatId = 1, resolution = 480)
        item(2, "Doraemon: Nobita and the Steel Troops", year = 2011); metadata(2, "Doraemon the Movie: Nobita and the Steel Troops", listOf("Animation"), year = 2011)
        file(2, "Doraemon.Steel.Troops.2011.1080p.BluRay.mkv", chatId = 1, quality = "BluRay")
        item(3, "Tom and Jerry", "SERIES", episodes = 4, watchedEpisodes = 4); metadata(3, "Tom and Jerry", listOf("Animation", "Comedy")); season(3, 1)
        file(3, "Tom.and.Jerry.S01E01.mkv", chatId = 1, resolution = 720)
        item(4, "Tom and Jerry Tales", "SERIES", episodes = 2); season(4, 1); file(4, "Tom.Jerry.Tales.E01.mp4", chatId = 1)
        item(5, "Ben 10", "SERIES", episodes = 20)
        val s1 = season(5, 1); val s2 = season(5, 2); season(5, 3)
        episode(5, s1, 1, 1); ben10S2E5 = episode(5, s2, 2, 5)
        file(5, "Ben10.S02E05.Hindi.720p.mkv", chatId = 1, resolution = 720)
        item(6, "Ben 10: Alien Force", "SERIES"); season(6, 1); season(6, 2); file(6, "Ben 10 Alien Force S01E01.mkv")
        item(7, "Motu Patlu", "SERIES"); metadata(7, "Motu Patlu", listOf("Animation", "Comedy")); language(7, "ta"); season(7, 1); file(7, "Motu Patlu Tamil E01.mp4", chatId = 1)
        item(8, "Comali", year = 2019); metadata(8, "Comali", listOf("Comedy", "Drama"), originalLanguage = "ta", rating = 7.0); language(8, "ta")
        file(8, "Comali.2019.Tamil.1080p.HDRip.mkv", chatId = 2, quality = "HDRip")
        item(9, "Vikram", year = 2022); metadata(9, "Vikram", listOf("Action", "Thriller"), originalLanguage = "ta", rating = 8.2); language(9, "ta", "te", "hi")
        file(9, "Vikram.2022.Tam+Tel+Hin.2160p.WEB-DL.mkv", chatId = 2, resolution = 2160)
        item(10, "Jerry Maguire", year = 1996); file(10, "Jerry.Maguire.1996.mkv", chatId = 2)
        item(11, "Spider-Man: Homecoming", year = 2017); metadata(11, "Spider-Man: Homecoming", listOf("Action")); file(11, "Spider-Man.Homecoming.2017.mkv")
        item(12, "Hidden Thing", hidden = true); file(12, "Hidden.mkv")
        item(13, "Gone Movie", available = 0)
        item(14, "Jailer", year = 2023, addedAt = 99); language(14, "ta"); file(14, "Jailer.2023.Tamil.mkv", chatId = 2)
        watch(14, positionMs = 30_000)
        item(15, "Leo", year = 2023); metadata(15, "Leo", listOf("Action", "Thriller"), provider = "cinemeta"); language(15, "ta"); file(15, "Leo.2023.mkv", chatId = 2)
        watch(15, positionMs = 100_000, completed = true)
    }

    @After fun close() = db.close()

    private fun search(text: String, filters: SearchFilters = SearchFilters(), sort: SearchSort = SearchSort.RELEVANCE, offset: Int = 0, limit: Int = 30) =
        runBlocking { engine.search(SearchRequest(text, filters, sort, offset = offset, limit = limit)) }

    private fun ids(text: String, filters: SearchFilters = SearchFilters(), sort: SearchSort = SearchSort.RELEVANCE) = search(text, filters, sort).hits.map { it.itemId }

    @Test
    fun specExamples() {
        assertEquals(1L, ids("doremon").first(), "doremon → Doraemon")
        assertTrue(search("doremon").interpretation.any { it.contains("doraemon") })
        val tj = ids("tom jerry")
        assertEquals(listOf(3L, 4L), tj.take(2), "tom jerry → Tom and Jerry, then Tales")
        assertTrue(10L !in tj, "Jerry Maguire lacks \"tom\"")

        val tc = search("tamil comedy")
        // Doraemon (Tamil audio, comedy), Motu Patlu and Comali satisfy both: they come first.
        assertEquals(setOf(1L, 7L, 8L), tc.hits.take(3).map { it.itemId }.toSet(), "Tamil + comedy satisfies both: first")
        assertTrue(tc.hits.take(3).all { h -> h.satisfied.any { it is SoftFilter.Language } && h.satisfied.any { it is SoftFilter.Genre } })
        assertTrue(tc.hits.drop(3).all { it.satisfied.size == 1 }, "items matching one of the two follow")

        val ben = search("ben 10 s2")
        assertEquals(5L, ben.hits.first().itemId)
        assertEquals(2, ben.hits.first().season)
        assertEquals(6L, ben.hits[1].itemId, "Alien Force also has a season 2")
        assertEquals(ben10S2E5, search("ben 10 s2e5").hits.first().episodeId, "s2e5 resolves the episode")
    }

    @Test
    fun exactPrefixFuzzyTokenAndCompoundMatching() {
        assertEquals(1L, ids("doraemon").first())
        assertEquals(1L, ids("dora").first(), "prefix while typing")
        assertEquals(10L, ids("jery maguire").single(), "typo")
        assertEquals(listOf(11L), ids("spiderman"), "compound")
        assertEquals(5L, ids("ben10").first(), "split compound")
        assertEquals(2L, ids("steel troops").single(), "words from the official title")
        assertEquals(2L, ids("nobita").single())
        assertTrue(ids("xyzzy").isEmpty())
    }

    @Test
    fun searchesFileNamesChatsQualityAndProvider() {
        assertEquals(listOf(9L), ids("tam+tel+hin"), "original file name words")
        assertEquals(listOf(2L), ids("bluray"), "release quality")
        assertTrue(ids("2160p").first() == 9L, "resolution")
        val kids = ids("in:kids")
        assertTrue(kids.containsAll(listOf(1L, 3L, 5L)) && 9L !in kids, "chat operator")
        assertEquals(listOf(15L), ids("provider:cinemeta"))
        assertTrue(ids("unmatched").containsAll(listOf(5L, 10L, 14L)), "items without metadata")
        assertTrue(1L !in ids("unmatched"))
    }

    @Test
    fun filtersAreHard() {
        val tamil = ids("", SearchFilters(languages = setOf("ta")))
        assertEquals(setOf(1L, 7L, 8L, 9L, 14L, 15L), tamil.toSet())
        assertEquals(setOf(8L), ids("", SearchFilters(languages = setOf("ta"), genres = setOf("comedy"), kinds = setOf(ItemKind.MOVIE))).toSet())
        assertEquals(setOf(14L, 15L), ids("", SearchFilters(yearFrom = 2023, yearTo = 2023)).toSet())
        assertEquals(setOf(9L), ids("", SearchFilters(minResolution = 2160)).toSet())
        assertEquals(setOf(5L, 6L), ids("", SearchFilters(season = 2)).toSet())
        assertEquals(setOf(8L, 9L, 10L, 14L, 15L), ids("", SearchFilters(chatIds = setOf(2L))).toSet())
        assertEquals(setOf(8L), ids("", SearchFilters(qualities = setOf("hdrip"))).toSet())
        assertEquals(setOf(3L, 15L), ids("", SearchFilters(watch = WatchState.WATCHED)).toSet())
        assertEquals(setOf(14L), ids("", SearchFilters(watch = WatchState.IN_PROGRESS)).toSet())
        assertEquals(listOf(14L), ids("jailer lang:ta year:2023"))
        assertTrue(ids("jailer", SearchFilters(languages = setOf("te"))).isEmpty())
        assertNull(ids("hidden").firstOrNull(), "hidden items are not found")
        assertEquals(listOf(12L), ids("hidden", SearchFilters(includeHidden = true)))
        assertTrue(13L !in ids("gone") && 13L in ids("gone", SearchFilters(includeUnavailable = true)))
        assertEquals(14L, ids("continue tamil").first(), "\"continue\" = in progress, and Tamil")
        assertTrue(search("unwatched 2023").hits.none { it.itemId == 15L && it.satisfied.size == 2 }, "Leo is watched")
    }

    @Test
    fun sortingAndPagination() {
        val byYear = ids("", SearchFilters(languages = setOf("ta"), kinds = setOf(ItemKind.MOVIE)), SearchSort.YEAR)
        assertEquals(setOf(14L, 15L), byYear.take(2).toSet(), "both 2023")
        assertEquals(9L, byYear[2])
        assertEquals(14L, ids("", SearchFilters(languages = setOf("ta")), SearchSort.RECENTLY_ADDED).first(), "added most recently")
        assertEquals(listOf(9L, 1L, 8L), ids("", SearchFilters(languages = setOf("ta")), SearchSort.RATING).take(3), "8.2, 7.5, 7.0")
        val byTitle = ids("", sort = SearchSort.TITLE)
        assertEquals(byTitle.size, byTitle.distinct().size)
        // Pages of 4: no gaps, no duplicates, totals agree.
        val all = mutableListOf<Long>()
        var offset: Int? = 0
        var total = -1
        while (offset != null) {
            val p = search("", sort = SearchSort.TITLE, offset = offset, limit = 4)
            total = p.total
            all += p.hits.map { it.itemId }
            offset = p.nextOffset
        }
        assertEquals(byTitle, all)
        assertEquals(13, total, "15 items − hidden − unavailable")
        val p1 = search("tom jerry", limit = 1)
        assertEquals(1, p1.hits.size); assertNotNull(p1.nextOffset)
        assertEquals(4L, search("tom jerry", offset = 1, limit = 1).hits.single().itemId, "text results page from the cached ranking")
    }

    @Test
    fun indexFollowsLibraryChangesThroughTriggers() {
        assertTrue(ids("dora").contains(1L))
        f.sql.execSQL("UPDATE library_item SET title = 'Doraemon Classic' WHERE id = 1")
        assertTrue(ids("classic").contains(1L), "rename indexed before the next search")
        f.sql.execSQL("UPDATE item_metadata SET genres = 'Horror' WHERE item_id = 8")
        assertTrue(8L in ids("", SearchFilters(genres = setOf("horror"))))
        f.sql.execSQL("DELETE FROM library_item WHERE id = 10")
        assertTrue(ids("maguire").isEmpty(), "deleted items disappear")
        f.sql.execSQL("UPDATE indexed_chat SET title = 'Super Toons' WHERE chat_id = 1")
        assertTrue(ids("in:toons").contains(1L), "chat rename re-indexes its items")
        assertTrue(runBlocking { engine.suggest("dor") }.contains("doraemon"))
    }
}
