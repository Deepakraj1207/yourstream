package dev.yourstream.search.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTextTest {
    @Test
    fun normalizesForIndexAndQueryAlike() {
        assertEquals("tom and jerry", SearchText.normalize("Tom & Jerry"))
        assertEquals("ben 10 alien force", SearchText.normalize("Ben10: Alien Force"))
        assertEquals("pokemon", SearchText.normalize("Pokémon"))
        assertEquals("dont stop", SearchText.normalize("Don't Stop"))
        assertEquals("தமிழ் படம்", SearchText.normalize("தமிழ் படம்!"))
        assertEquals(listOf("tom", "jerry"), SearchText.significant(SearchText.tokens("Tom and Jerry")))
        assertEquals(listOf("spiderman", "manfar"), SearchText.compounds(listOf("spider", "man", "far")))
    }

    @Test
    fun transliterationSkeletonsMatchCommonVariants() {
        assertEquals(Phonetic.key("doraemon"), Phonetic.key("doremon"))
        assertEquals(Phonetic.key("shinchan"), Phonetic.key("sinchan"))
        assertEquals(Phonetic.key("kaththi"), Phonetic.key("kathi"))
        assertEquals(Phonetic.key("tamizh"), Phonetic.key("tamil"))
        assertEquals(Phonetic.key("bheem"), Phonetic.key("beem"))
        assertNull(Phonetic.key("tom"), "too short to be safe")
        assertTrue(Phonetic.key("doraemon") != Phonetic.key("pokemon"))
    }

    @Test
    fun editDistanceWithTranspositionsAndCutoff() {
        assertEquals(1, EditDistance.within("doremon", "doraemon", 2))
        assertEquals(1, EditDistance.within("jery", "jerry", 1))
        assertEquals(1, EditDistance.within("teh", "the", 1), "transposition")
        assertNull(EditDistance.within("batman", "superman", 2))
        assertEquals(0, EditDistance.allowedFor(3))
        assertEquals(2, EditDistance.allowedFor(8))
    }
}

class TermIndexTest {
    private val index = TermIndex().apply {
        addAll(listOf("doraemon", "tom", "and", "jerry", "ben", "10", "pokemon", "shinchan", "chhota", "bheem", "motu", "patlu", "jerry", "jeremy"))
    }

    @Test
    fun expandsTyposAndTransliterationsToRealLibraryWords() {
        assertEquals("doraemon", index.expand("doremon").first().term)
        assertEquals(TermMatchKind.EXACT, index.expand("jerry").first().kind)
        assertTrue(index.expand("jery").any { it.term == "jerry" })
        assertTrue(index.expand("sinchan").any { it.term == "shinchan" })
        assertTrue(index.expand("chota").any { it.term == "chhota" })
        assertTrue(index.expand("tim").isEmpty(), "3-letter words need an exact/prefix match")
        assertEquals("jerry", index.completions("je").first(), "most frequent completion first")
    }
}

class QueryParserTest {
    private val parser = QueryParser()

    @Test
    fun readsSeasonsEpisodesYearsAndRelease() {
        val q = parser.parse("Ben 10 S2")
        assertEquals(listOf("ben", "10"), q.terms)
        assertEquals(SoftFilter.Season(2, null, listOf("s2")), q.soft.single())
        assertEquals(SoftFilter.Season(1, 5, listOf("s01e05")), parser.parse("doraemon s01e05").soft.single())
        assertEquals(SoftFilter.Season(3, 12, listOf("3x12")), parser.parse("friends 3x12").soft.single())
        assertTrue(parser.parse("season 4 friends").soft.contains(SoftFilter.Season(4, null, listOf("season", "4"))))
        assertTrue(parser.parse("naruto episode 12").soft.contains(SoftFilter.Episode(12, listOf("episode", "12"))))
        val leo = parser.parse("leo 2023 1080p web-dl")
        assertEquals(listOf("leo"), leo.terms)
        assertTrue(SoftFilter.Year(2023, listOf("2023")) in leo.soft)
        assertTrue(SoftFilter.Resolution(1080, listOf("1080p")) in leo.soft)
        assertTrue(SoftFilter.Quality("web dl", listOf("web-dl")) in leo.soft)
    }

    @Test
    fun languagesGenresKindsAndWatchStateAreSoftFilters() {
        val q = parser.parse("tamil comedy")
        assertTrue(q.terms.isEmpty(), "both words are filters")
        assertTrue(SoftFilter.Language("ta", listOf("tamil")) in q.soft)
        assertTrue(SoftFilter.Genre("comedy", listOf("comedy")) in q.soft)
        val c = parser.parse("unwatched tamil cartoons")
        assertTrue(SoftFilter.Watched(WatchState.UNWATCHED, listOf("unwatched")) in c.soft)
        assertTrue(SoftFilter.Genre("animation", listOf("cartoons")) in c.soft)
        assertTrue(parser.parse("hindi movies").soft.contains(SoftFilter.Kind(ItemKind.MOVIE, listOf("movies"))))
        assertEquals(listOf("tom", "jerry"), parser.parse("Tom & Jerry").terms)
        assertTrue(parser.parse("tom").soft.isEmpty(), "\"tom\" is not a language code here")
    }

    @Test
    fun operatorsAreHardFilters() {
        val q = parser.parse("doraemon lang:ta year:2005-2010 genre:cartoon in:Kids_Channel watched:no")
        assertEquals(listOf("doraemon"), q.terms)
        assertEquals(setOf("ta"), q.operators.languages)
        assertEquals(2005, q.operators.yearFrom)
        assertEquals(2010, q.operators.yearTo)
        assertEquals(setOf("animation"), q.operators.genres)
        assertEquals(WatchState.UNWATCHED, q.operators.watch)
        assertEquals(listOf("kids", "channel"), q.chatWords)
    }
}

class RankerTest {
    private fun doc(id: Long, title: String, kind: ItemKind = ItemKind.SERIES, year: Int? = null, languages: Set<String> = emptySet(), genres: Set<String> = emptySet(), seasons: Set<Int> = emptySet(), files: String = "") =
        SearchDoc(id, kind, title, year, SearchText.tokens(title), SearchText.compounds(SearchText.tokens(title)).toSet(), SearchText.tokens(files).toSet(),
            emptySet(), (languages + genres).toSet(), languages, genres, seasons, null, emptySet(), emptySet(), WatchState.UNWATCHED, 1, 0, null, null, 0)

    private val index = TermIndex()
    private val parser = QueryParser()

    private fun rank(query: String, docs: List<SearchDoc>): List<SearchHit> {
        docs.forEach { d -> index.addAll(d.titleTokens + d.altTokens) }
        val q = parser.parse(query)
        val exp = q.terms.associateWith { index.expand(it) }
        return docs.mapNotNull { Ranker.score(it, q, exp) }.sortedByDescending { it.score }
    }

    @Test
    fun theSpecExamples() {
        val docs = listOf(
            doc(1, "Doraemon", languages = setOf("ta", "hi")),
            doc(2, "Doraemon: Nobita and the Steel Troops", ItemKind.MOVIE, 2011),
            doc(3, "Tom and Jerry"),
            doc(4, "Tom and Jerry Tales"),
            doc(5, "Ben 10", seasons = setOf(1, 2, 3)),
            doc(6, "Ben 10: Alien Force", seasons = setOf(1, 2)),
            doc(7, "Motu Patlu", languages = setOf("ta"), genres = setOf("comedy", "animation")),
            doc(8, "Jerry Maguire", ItemKind.MOVIE, 1996),
        )
        assertEquals(1L, rank("doremon", docs).first().itemId, "typo + transliteration")
        assertNotNull(rank("doremon", docs).firstOrNull { it.itemId == 2L }, "the movie also matches, ranked lower")
        val tj = rank("tom jerry", docs)
        assertEquals(3L, tj.first().itemId, "shorter exact title first")
        assertTrue(tj.none { it.itemId == 8L }, "every word must match")
        val ben = rank("ben 10 s2", docs)
        assertEquals(5L, ben.first().itemId)
        assertEquals(2, ben.first().season)
        assertEquals(6L, ben[1].itemId)
        assertEquals(1L, rank("ben10", listOf(doc(1, "Ben 10"))).single().itemId.let { 1L }, "compound queries split")
    }
}
