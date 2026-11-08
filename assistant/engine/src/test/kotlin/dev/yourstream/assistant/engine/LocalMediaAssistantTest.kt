package dev.yourstream.assistant.engine

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.assistant.core.AssistantInterpreter
import dev.yourstream.assistant.core.Intent
import dev.yourstream.assistant.core.IntentModel
import dev.yourstream.assistant.core.IntentParser
import dev.yourstream.assistant.core.InterpretationSource
import dev.yourstream.assistant.core.QueryPlan
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.WatchState
import dev.yourstream.search.engine.LibrarySearchEngine
import dev.yourstream.search.engine.SearchIndexer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalMediaAssistantTest {
    private val db = YourStreamDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val indexer = SearchIndexer(db)
    private val assistant = LocalMediaAssistant(db, LibrarySearchEngine(db, indexer), indexer, Optional.empty())
    private val f = AssistantFixture(db)
    private var doraemonS2E10 = 0L
    private var shinchanS2E10 = 0L
    private var ben10S1E3 = 0L
    private var ben10S2E1 = 0L

    // ids
    private val doraemon = 1L
    private val ben10 = 2L
    private val comali = 3L
    private val vikram = 4L
    private val jailer = 5L
    private val pushpa = 6L
    private val baahubali = 7L
    private val ala = 8L
    private val chandramukhi = 9L
    private val anniyan = 10L
    private val tomJerry = 11L
    private val shinchan = 12L
    private val hidden = 13L

    @Before fun library() = f.transaction {
        item(doraemon, "Doraemon", "SERIES", episodes = 13); metadata(doraemon, "Doraemon", listOf("Animation", "Comedy", "Family"), rating = 7.5); language(doraemon, "ta", "hi")
        season(doraemon, 1).let { s -> (1..3).forEach { e -> episode(doraemon, s, 1, e) } }
        season(doraemon, 2).let { s -> (1..10).forEach { e -> val id = episode(doraemon, s, 2, e); if (e == 10) doraemonS2E10 = id } }
        file(doraemon, "Doraemon.S01E01.Tamil.mkv")

        item(ben10, "Ben 10", "SERIES", episodes = 6, watchedEpisodes = 2, lastWatchedAt = 5_000); metadata(ben10, "Ben 10", listOf("Action", "Animation")); language(ben10, "hi")
        val b1 = season(ben10, 1); val b2 = season(ben10, 2)
        val e1 = episode(ben10, b1, 1, 1); val e2 = episode(ben10, b1, 1, 2); ben10S1E3 = episode(ben10, b1, 1, 3)
        ben10S2E1 = episode(ben10, b2, 2, 1); episode(ben10, b2, 2, 2); episode(ben10, b2, 2, 3)
        watchEpisode(ben10, e1, at = 4_000); watchEpisode(ben10, e2, at = 5_000)
        file(ben10, "Ben10.S01E01.Hindi.mkv")

        movie(comali, "Comali", 2019, "ta", listOf("Comedy", "Drama"), 7.0)
        movie(vikram, "Vikram", 2022, "ta", listOf("Action", "Thriller"), 8.2); watch(vikram, 100_000, completed = true, at = 9_000)
        movie(jailer, "Jailer", 2023, "ta", listOf("Action", "Comedy"), 7.1); watch(jailer, 30_000, at = 10_000)
        movie(pushpa, "Pushpa", 2021, "te", listOf("Action", "Drama"), 7.6)
        movie(baahubali, "Baahubali", 2015, "te", listOf("Action", "Fantasy"), 8.0); watch(baahubali, 100_000, completed = true, at = 8_000)
        movie(ala, "Ala Vaikunthapurramuloo", 2020, "te", listOf("Comedy", "Family"), 7.3)
        movie(chandramukhi, "Chandramukhi", 2005, "ta", listOf("Comedy", "Horror"), 6.9)
        movie(anniyan, "Anniyan", 2005, "ta", listOf("Thriller"), 7.8)

        item(tomJerry, "Tom and Jerry", "SERIES", episodes = 2); metadata(tomJerry, "Tom and Jerry", listOf("Animation", "Comedy")); language(tomJerry, "en")
        season(tomJerry, 1).let { s -> episode(tomJerry, s, 1, 1); episode(tomJerry, s, 1, 2) }
        file(tomJerry, "Tom.and.Jerry.S01E01.mkv")
        item(shinchan, "Shinchan", "SERIES", episodes = 10); language(shinchan, "hi")
        season(shinchan, 2).let { s -> (1..10).forEach { e -> val id = episode(shinchan, s, 2, e); if (e == 10) shinchanS2E10 = id } }
        file(shinchan, "Shinchan.S02E10.mkv")
        item(hidden, "Hidden Tamil Movie", year = 2005, hidden = true); language(hidden, "ta"); file(hidden, "Hidden.mkv")
    }

    private fun AssistantFixture.movie(id: Long, title: String, year: Int, lang: String, genres: List<String>, rating: Double) {
        item(id, title, year = year); metadata(id, title, genres, rating = rating, year = year); language(id, lang); file(id, "$title.$year.mkv")
    }

    @After fun close() = db.close()

    private fun ask(q: String) = runBlocking { assistant.ask(q) }
    private fun ids(q: String) = ask(q).items.map { it.itemId }

    @Test
    fun showTamilMovies() {
        val r = ask("show Tamil movies")
        assertEquals(Intent.SEARCH, r.interpretation.intent)
        assertEquals(setOf(comali, vikram, jailer, chandramukhi, anniyan), r.items.map { it.itemId }.toSet(), "hidden excluded, series excluded")
        assertTrue(r.items.all { it.kind == ItemKind.MOVIE })
        assertEquals("5 Tamil movies.", r.message)
    }

    @Test
    fun findDoraemonEpisodes() {
        val r = ask("find Doraemon episodes")
        val plan = assertIs<QueryPlan.Episodes>(r.plan)
        assertEquals("doraemon", plan.seriesTitle)
        assertEquals(13, r.total)
        assertEquals(1 to 1, r.items.first().season to r.items.first().episode)
        assertEquals(2 to 10, r.items.last().season to r.items.last().episode)
        assertTrue(r.items.all { it.itemId == doraemon && it.episodeId != null })
        assertEquals("13 episodes of Doraemon.", r.message)
        // Typos still resolve the series (through the search engine).
        assertEquals(13, ask("doremon episodes").total)
        assertEquals(10, ask("doraemon season 2 episodes").total)
    }

    @Test
    fun continueWatchingBen10() {
        val r = ask("continue watching Ben 10")
        assertIs<QueryPlan.ContinueWatching>(r.plan)
        val next = r.items.single()
        assertEquals(ben10S1E3, next.episodeId, "S1E1 and S1E2 are finished")
        assertEquals("next episode", next.reason)
        assertEquals("Next: Ben 10 S01E03.", r.message)
        // An in-progress movie resumes.
        ask("resume jailer").items.single().let { assertEquals(jailer, it.itemId); assertEquals(0.3f, it.progress) }
        // Without a title: everything in progress, then series with a next episode.
        assertEquals(listOf(jailer, ben10), ids("continue watching"))
    }

    @Test
    fun unwatchedComedyMovies() {
        val r = ask("show unwatched comedy movies")
        assertEquals(setOf(comali, ala, chandramukhi), r.items.map { it.itemId }.toSet(), "Jailer is in progress, Doraemon is a series")
        assertEquals("3 unwatched comedy movies.", r.message)
    }

    @Test
    fun moviesFrom2005() {
        val r = ask("find movies from 2005")
        assertEquals(setOf(chandramukhi, anniyan), r.items.map { it.itemId }.toSet())
        assertEquals("2 movies from 2005.", r.message)
    }

    @Test
    fun teluguMoviesNotWatched() {
        assertEquals(setOf(pushpa, ala), ids("show Telugu movies I haven't watched").toSet(), "Baahubali is watched")
        assertEquals(listOf(baahubali), ids("telugu movies I have seen"))
        assertEquals(setOf(comali, chandramukhi, anniyan), ids("unwatched Tamil movies").toSet(), "mediaType=MOVIE, language=Tamil, watched=false")
    }

    @Test
    fun season2Episode10() {
        val r = ask("find season 2 episode 10")
        assertEquals(Intent.EPISODES, r.interpretation.intent)
        assertEquals(setOf(doraemonS2E10, shinchanS2E10), r.items.map { it.episodeId }.toSet())
        assertEquals("2 episodes in season 2, episode 10 across 2 series.", r.message)
        assertEquals(listOf(doraemonS2E10), ask("doraemon season 2 episode 10").items.map { it.episodeId })
    }

    @Test
    fun whatShouldIWatchNext() {
        val r = ask("what should I watch next?")
        assertIs<QueryPlan.Recommend>(r.plan)
        val items = r.items
        assertEquals(jailer, items[0].itemId, "in progress first")
        assertEquals(ben10S1E3, items[1].episodeId, "then the next episode of a started series")
        val fresh = items.drop(2)
        assertTrue(fresh.none { it.itemId in setOf(vikram, baahubali, hidden) }, "nothing watched or hidden")
        // Everything watched is action (Tamil/Telugu): the top fresh pick is an unwatched action movie.
        assertEquals(pushpa, fresh.first().itemId)
        assertTrue(r.message.startsWith("Based on what you watch"))
        // Constrained recommendations: only unwatched comedy.
        val comedy = ask("recommend a comedy")
        assertTrue(comedy.items.isNotEmpty() && comedy.items.none { it.itemId == jailer })
        assertEquals(setOf(ItemKind.SERIES), ask("suggest tamil series").items.map { it.kind }.toSet())
    }

    @Test
    fun unwatchedEpisodesAndPlay() {
        val r = ask("unwatched episodes of ben 10")
        assertEquals(4, r.total)
        assertEquals(ben10S1E3, r.items.first().episodeId)
        assertEquals(listOf(doraemonS2E10), ask("play doraemon s2e10").items.map { it.episodeId })
        assertEquals(listOf(ben10S1E3), ask("play ben 10").items.map { it.episodeId }, "play a series = its next episode")
        assertEquals(listOf(ben10S2E1), ask("play ben 10 season 2").items.map { it.episodeId })
        assertEquals(listOf(comali), ids("play comali"))
        assertTrue(ask("play xyzzy").items.isEmpty())
    }

    @Test
    fun unknownSeriesFallsBackToSearch() {
        val r = ask("comali episodes")
        assertTrue(r.message.startsWith("No series called \"comali\""))
        assertEquals(listOf(comali), r.items.map { it.itemId })
    }

    @Test
    fun optionalModelIsUsedOnlyWhenUnsure() {
        val model = IntentModel { _, _ -> """{"intent":"search","mediaType":"movie","languages":["telugu"],"watched":false}""" }
        assistant.interpreter = AssistantInterpreter(IntentParser(), model)
        val r = ask("anything nice tonight")
        assertEquals(InterpretationSource.MODEL, r.interpretation.source)
        assertEquals(setOf(pushpa, ala), r.items.map { it.itemId }.toSet())
        // A confident deterministic parse never goes to the model.
        assertEquals(InterpretationSource.DETERMINISTIC, ask("show Tamil movies").interpretation.source)
        assertEquals(WatchState.ANY, ask("show Tamil movies").interpretation.entities.watch)
    }
}
