package dev.yourstream.assistant.core

import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.WatchState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentParserTest {
    private val parser = IntentParser(currentYear = { 2026 })
    private fun p(s: String) = parser.parse(s)
    private fun e(s: String) = p(s).entities

    @Test
    fun specExamples() {
        p("show Tamil movies").let {
            assertEquals(Intent.SEARCH, it.intent)
            assertEquals(Entities(kinds = setOf(ItemKind.MOVIE), languages = setOf("ta")), it.entities)
        }
        p("find Doraemon episodes").let {
            assertEquals(Intent.EPISODES, it.intent)
            assertEquals("doraemon", it.entities.title)
            assertEquals(setOf(ItemKind.SERIES), it.entities.kinds)
        }
        p("continue watching Ben 10").let {
            assertEquals(Intent.CONTINUE_WATCHING, it.intent)
            assertEquals("ben 10", it.entities.title)
        }
        assertEquals(Entities(kinds = setOf(ItemKind.MOVIE), genres = setOf("comedy"), watch = WatchState.UNWATCHED), e("show unwatched comedy movies"))
        p("find movies from 2005").let {
            assertEquals(Intent.SEARCH, it.intent)
            assertEquals(Entities(kinds = setOf(ItemKind.MOVIE), yearFrom = 2005, yearTo = 2005), it.entities)
        }
        assertEquals(Entities(kinds = setOf(ItemKind.MOVIE), languages = setOf("te"), watch = WatchState.UNWATCHED), e("show Telugu movies I haven't watched"))
        p("find season 2 episode 10").let {
            assertEquals(Intent.EPISODES, it.intent)
            assertEquals(Entities(season = 2, episode = 10), it.entities)
        }
        p("what should I watch next?").let {
            assertEquals(Intent.RECOMMEND, it.intent)
            assertEquals(Entities(), it.entities)
            assertTrue(it.confidence >= 0.9)
        }
        // mediaType = MOVIE, language = Tamil, watched = false
        assertEquals(Entities(kinds = setOf(ItemKind.MOVIE), languages = setOf("ta"), watch = WatchState.UNWATCHED), e("unwatched Tamil movies"))
    }

    @Test
    fun watchedStatePhrasings() {
        for (q in listOf("tamil movies I have not seen", "tamil movies I never watched", "tamil movies not watched yet", "unseen tamil movies", "Tamil movies I haven’t watched"))
            assertEquals(WatchState.UNWATCHED, e(q).watch, q)
        for (q in listOf("movies I already watched", "tamil movies I've seen", "finished series"))
            assertEquals(WatchState.WATCHED, e(q).watch, q)
        for (q in listOf("movies I didn't finish", "shows in progress", "half watched movies"))
            assertEquals(WatchState.IN_PROGRESS, e(q).watch, q)
        assertNull(e("I have not watched").title, "\"not\" and \"watched\" consumed")
    }

    @Test
    fun yearsAndRanges() {
        e("hindi movies between 2010 and 2015").let { assertEquals(2010 to 2015, it.yearFrom to it.yearTo); assertEquals(setOf("hi"), it.languages) }
        e("movies 2010-2015").let { assertEquals(2010 to 2015, it.yearFrom to it.yearTo) }
        e("movies from 2001 to 2003").let { assertEquals(2001 to 2003, it.yearFrom to it.yearTo) }
        e("movies after 2020").let { assertEquals(2021 to null, it.yearFrom to it.yearTo) }
        e("films since 2020").let { assertEquals(2020 to null, it.yearFrom to it.yearTo) }
        e("movies before 2000").let { assertEquals(null to 1999, it.yearFrom to it.yearTo) }
        e("90s tamil movies").let { assertEquals(1990 to 1999, it.yearFrom to it.yearTo); assertNull(it.title) }
        e("movies of the 2000s").let { assertEquals(2000 to 2009, it.yearFrom to it.yearTo) }
        e("movies from this year").let { assertEquals(2026 to 2026, it.yearFrom to it.yearTo) }
        e("last year comedy").let { assertEquals(2025 to 2025, it.yearFrom to it.yearTo); assertEquals(setOf("comedy"), it.genres) }
    }

    @Test
    fun seasonsAndEpisodes() {
        e("ben 10 s2e5").let { assertEquals(Triple("ben 10", 2, 5), Triple(it.title, it.season, it.episode)) }
        e("doraemon 1x12").let { assertEquals(Triple("doraemon", 1, 12), Triple(it.title, it.season, it.episode)) }
        e("tom and jerry season two episode three").let { assertEquals(Triple("tom and jerry", 2, 3), Triple(it.title, it.season, it.episode)) }
        e("second season of motu patlu").let { assertEquals("motu patlu" to 2, it.title to it.season) }
        e("ben 10 s3").let { assertEquals("ben 10" to 3, it.title to it.season) }
        assertEquals(Intent.EPISODES, p("doraemon season 3").intent)
        assertEquals(Intent.EPISODES, p("unwatched episodes of shinchan").intent)
        assertEquals("shinchan", e("unwatched episodes of shinchan").title)
        p("play doraemon season 1 episode 3").let {
            assertEquals(Intent.PLAY, it.intent)
            assertEquals(Triple("doraemon", 1, 3), Triple(it.entities.title, it.entities.season, it.entities.episode))
        }
    }

    @Test
    fun genresLanguagesAndTypes() {
        assertEquals(setOf("animation"), e("tamil cartoons").genres)
        assertEquals(setOf("ta"), e("tamil cartoons").languages)
        assertEquals(setOf("science fiction"), e("sci-fi movies").genres)
        assertEquals(setOf("science fiction"), e("science fiction films").genres)
        assertEquals(setOf("comedy", "romance"), e("romantic comedies").genres)
        assertEquals(setOf("horror"), e("scary movies").genres)
        assertEquals(setOf("hi", "en"), e("hindi or english action movies").languages)
        assertEquals(setOf("hi"), e("movies dubbed in Hindi").languages)
        assertNull(e("movies dubbed in Hindi").title)
        assertEquals(setOf(ItemKind.SERIES), e("telugu web series").kinds)
        assertEquals(setOf(ItemKind.SERIES), e("show me tv shows").kinds)
        assertTrue(e("ben 10").languages.isEmpty(), "\"ben\" is not Bengali")
    }

    @Test
    fun sortLimitAndResolution() {
        e("top 5 rated tamil movies").let { assertEquals(5, it.limit) }
        e("top rated comedy").let { assertEquals(SearchSort.RATING, it.sort); assertNull(it.title) }
        e("latest malayalam movies").let { assertEquals(SearchSort.RECENTLY_ADDED, it.sort); assertEquals(setOf("ml"), it.languages) }
        e("oldest movies").let { assertEquals(SearchSort.YEAR, it.sort); assertTrue(it.reversed) }
        e("show 10 movies").let { assertEquals(10, it.limit) }
        e("vikram in 4k").let { assertEquals("vikram" to 2160, it.title to it.minResolution) }
        e("1080p action movies").let { assertEquals(1080, it.minResolution) }
    }

    @Test
    fun intentsAndTitles() {
        assertEquals(Intent.RECOMMEND, p("recommend a comedy").intent)
        assertEquals(setOf("comedy"), e("recommend a comedy").genres)
        assertEquals(Intent.RECOMMEND, p("suggest something to watch in Tamil").intent)
        assertEquals(setOf("ta"), e("suggest something to watch in Tamil").languages)
        assertEquals(Intent.CONTINUE_WATCHING, p("resume").intent)
        assertEquals(Intent.CONTINUE_WATCHING, p("what was I watching? continue").intent)
        assertEquals(Intent.SEARCH, p("doraemon").intent)
        assertEquals("spider man homecoming", e("find Spider-Man: Homecoming").title)
        assertEquals("tom and jerry", e("can you show me tom and jerry please").title)
        assertTrue(p("show Tamil movies").confidence > p("doraemon").confidence)
        assertTrue(p("").confidence < 0.5)
        assertTrue(p("show Tamil movies").notes.any { "Tamil" in it }, "explains what was recognised")
    }
}
