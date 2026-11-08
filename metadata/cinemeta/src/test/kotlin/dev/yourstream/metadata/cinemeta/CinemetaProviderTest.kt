package dev.yourstream.metadata.cinemeta

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.network.HttpClients
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CinemetaProviderTest {

    private val paths = mutableListOf<String>()

    private fun provider(body: (String) -> Pair<HttpStatusCode, String>) = CinemetaProvider(
        HttpClients.create(MockEngine { req ->
            paths += req.url.encodedPath
            val (status, text) = body(req.url.encodedPath)
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    private val seriesMeta = """{"meta":{"id":"tt0903747","type":"series","name":"Breaking Bad","releaseInfo":"2008–2013","description":"Walter",
        "poster":"https://img/p.jpg","genres":["Drama","Crime"],"cast":["Bryan Cranston","Aaron Paul"],"imdbRating":"9.5",
        "videos":[{"id":"tt0903747:1:1","season":1,"episode":1,"name":"Pilot","released":"2008-01-20T00:00:00.000Z"},
                  {"id":"tt0903747:1:2","season":1,"episode":2,"name":"Cat's in the Bag"},
                  {"id":"tt0903747:2:1","season":2,"episode":1,"name":"Seven Thirty-Seven"}]}}"""

    @Test
    fun searchUsesTheCatalogResourceAndImdbIds() = runTest {
        val p = provider { HttpStatusCode.OK to """{"metas":[{"id":"tt1375666","type":"movie","name":"Inception","releaseInfo":"2010","poster":"p"},
            {"id":"tt0","type":"movie","name":"Inception Making Of","releaseInfo":"2015"}]}""" }
        val results = (p.search(MetadataQuery("Inception", 2010, MetadataType.MOVIE)) as AppResult.Ok).value
        assertEquals(listOf("tt1375666"), results.map { it.ref.id }, "year filter applied locally")
        assertEquals("tt1375666", results.single().ref.imdbId)
        assertEquals("/catalog/movie/top/search=Inception.json", paths.single())
    }

    @Test
    fun seriesSeasonsAndEpisodesComeFromVideos() = runTest {
        val p = provider { HttpStatusCode.OK to seriesMeta }
        val s = (p.series("tt0903747", null) as AppResult.Ok).value
        assertEquals(2008, s.year)
        assertEquals(listOf(1 to 2, 2 to 1), s.seasons.map { it.number to it.episodeCount })
        assertEquals(listOf("Bryan Cranston", "Aaron Paul"), s.cast.map { it.name })
        val season1 = (p.season("tt0903747", 1, null) as AppResult.Ok).value
        assertEquals(listOf("Pilot", "Cat's in the Bag"), season1.episodes.map { it.title })
        assertEquals("2008-01-20", season1.episodes.first().airDate)
        assertEquals("Seven Thirty-Seven", (p.episode("tt0903747", 2, 1, null) as AppResult.Ok).value.title)
        assertIs<AppError.NotFound>((p.episode("tt0903747", 9, 1, null) as AppResult.Err).error)
    }

    @Test
    fun missingMetaIsNotFound() = runTest {
        assertIs<AppError.NotFound>((provider { HttpStatusCode.OK to """{"meta":null}""" }.movie("tt9", null) as AppResult.Err).error)
        assertIs<AppError.NotFound>((provider { HttpStatusCode.NotFound to "" }.movie("tt9", null) as AppResult.Err).error)
    }
}
