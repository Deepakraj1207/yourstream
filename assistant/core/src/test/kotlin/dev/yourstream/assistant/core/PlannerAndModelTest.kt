package dev.yourstream.assistant.core

import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.WatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerAndModelTest {
    private val parser = IntentParser(currentYear = { 2026 })
    private fun plan(s: String) = Planner.plan(parser.parse(s))

    @Test
    fun plans() {
        val tamil = assertIs<QueryPlan.Search>(plan("unwatched Tamil movies")).request
        assertEquals("", tamil.text)
        assertEquals(setOf(ItemKind.MOVIE), tamil.filters.kinds)
        assertEquals(setOf("ta"), tamil.filters.languages)
        assertEquals(WatchState.UNWATCHED, tamil.filters.watch)
        assertEquals(SearchSort.RECENTLY_ADDED, tamil.sort, "no title words: newest first")
        assertEquals(SearchSort.RELEVANCE, assertIs<QueryPlan.Search>(plan("vikram")).request.sort)
        assertEquals(QueryPlan.Episodes("doraemon", null, null, WatchState.ANY, emptySet(), 100), plan("find Doraemon episodes"))
        assertEquals(QueryPlan.Episodes(null, 2, 10, WatchState.ANY, emptySet(), 100), plan("find season 2 episode 10"))
        assertEquals(QueryPlan.ContinueWatching("ben 10", 20), plan("continue watching Ben 10"))
        assertEquals(QueryPlan.Recommend(emptySet(), emptySet(), emptySet(), 10), plan("what should I watch next?"))
        assertEquals(QueryPlan.Play("doraemon", 1, 3), plan("play doraemon s1e3"))
        assertIs<QueryPlan.Search>(plan("play"), "nothing to play: search")
    }

    @Test
    fun noModelMeansDeterministic() = runTest {
        val i = AssistantInterpreter(parser).interpret("tom jerry")
        assertEquals(InterpretationSource.DETERMINISTIC, i.source)
        assertEquals("tom jerry", i.entities.title)
    }

    @Test
    fun modelFillsGapsButParserEntitiesWin() = runTest {
        val model = IntentModel { _, _ ->
            """Sure! {"intent":"recommend","languages":["Tamil","xx"],"genres":["Funny","nonsense"],"mediaType":"film","watched":false,"yearFrom":3000,"extra":1}"""
        }
        val i = AssistantInterpreter(parser, model).interpret("anything tonight for the family")
        assertEquals(InterpretationSource.MODEL, i.source)
        assertEquals(Intent.RECOMMEND, i.intent)
        assertEquals(setOf("ta"), i.entities.languages, "unknown language dropped")
        assertEquals(setOf("family"), i.entities.genres, "the parser's genre wins")
        assertEquals(setOf(ItemKind.MOVIE), i.entities.kinds)
        assertEquals(WatchState.UNWATCHED, i.entities.watch)
        assertNull(i.entities.yearFrom, "implausible year rejected")
    }

    @Test
    fun confidentParsesSkipTheModel() = runTest {
        var called = false
        val i = AssistantInterpreter(parser, { _, _ -> called = true; """{"intent":"play"}""" }).interpret("show Tamil movies")
        assertEquals(Intent.SEARCH, i.intent)
        assertTrue(!called)
    }

    @Test
    fun brokenModelsFallBack() = runTest {
        val base = parser.parse("tonight")
        for (model in listOf(
            IntentModel { _, _ -> "I think you want a movie" },
            IntentModel { _, _ -> "{not json" },
            IntentModel { _, _ -> null },
            IntentModel { _, _ -> error("model crashed") },
            IntentModel { _, _ -> delay(10_000); """{"intent":"play"}""" },
        )) {
            val i = AssistantInterpreter(parser, model, timeoutMs = 100).interpret("tonight")
            assertEquals(InterpretationSource.DETERMINISTIC, i.source)
            assertEquals(base.intent, i.intent)
            assertEquals(base.entities, i.entities)
        }
    }
}
