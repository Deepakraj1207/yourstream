package dev.yourstream.catalog.indian

import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.stremio.StremioAddonDefinition
import dev.yourstream.catalog.stremio.StremioCatalogProvider
import dev.yourstream.catalog.stremio.StremioIds
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.network.HttpClients
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String) = IndianCatalogProvidersTest::class.java.getResource("/fixtures/$name")!!.readText()

class IndianCatalogProvidersTest {

    private val requested = mutableListOf<String>()

    /** Routes request URLs to fixtures; anything else 404s. [down] hosts fail with 503. */
    private fun provider(definition: StremioAddonDefinition, down: Set<String> = emptySet(), routes: Map<String, String>): StremioCatalogProvider {
        val engine = MockEngine { req ->
            val url = req.url.toString()
            requested += url
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                down.any { url.contains(it) } -> respond("", HttpStatusCode.ServiceUnavailable)
                else -> routes.entries.firstOrNull { url.endsWith(it.key) }?.let { respond(fixture(it.value), HttpStatusCode.OK, json) }
                    ?: respond("Not found", HttpStatusCode.NotFound)
            }
        }
        return StremioCatalogProvider(HttpClients.create(engine), definition, requestsPerSecond = 1000)
    }

    private val indiaStreams get() = provider(IndianCatalogs.INDIA_STREAMS, routes = mapOf(
        "/manifest.json" to "indiastreams-manifest.json",
        "/catalog/movie/trendingmovies.json" to "indiastreams-trending.json",
    ))

    private fun regional(down: Set<String> = emptySet()) = provider(IndianCatalogs.INDIAN_REGIONAL, down, mapOf(
        "/manifest.json" to "indian-regional-manifest.json",
        "/catalog/movie/ta.json" to "indian-regional-ta.json",
        "/catalog/movie/ta/skip=20.json" to "indian-regional-ta.json",
        "/meta/movie/tt2000002.json" to "empty-array.json",
        "/meta/movie/tmdb%3A1900001%7C%7CSample%20Tamil%20Film%7C%7C2026%7C%7Cmovie%7C%7Chttps%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Foriginal%2Fabc.jpg%7C%7CA%2520young%2520filmmaker%2520chases%2520a%2520dream..json" to "indian-regional-meta-tmdb.json",
    ))

    @Test
    fun indiaStreamsManifest() = runTest {
        val info = (indiaStreams.describe() as AppResult.Ok).value
        assertEquals(8, info.catalogs.size)
        assertEquals(listOf("catalog"), info.resources)
        assertFalse(info.supportsMeta)
        assertTrue(info.catalogs.none { it.supportsPaging || it.supportsSearch })
        assertEquals(listOf("trending"), info.catalogs.first { it.id == "trendingmovies" }.tags)
        assertEquals(listOf("ott"), info.catalogs.first { it.id == "hstzee" }.tags)
        assertTrue(info.languages.isEmpty(), "IndiaStreams lists mix languages")
    }

    @Test
    fun indiaStreamsItemsToleratesNumericFields() = runTest {
        val p = indiaStreams
        val catalog = (p.describe() as AppResult.Ok).value.catalogs.first { it.id == "trendingmovies" }
        val page = (p.page(catalog) as AppResult.Ok).value
        val first = page.items.first()
        assertEquals("tt1000001", first.ref.imdbId)
        assertEquals(2025, first.year, "releaseInfo given as a number")
        assertEquals(114, first.runtimeMinutes)
        assertEquals(7.3, first.rating)
        assertEquals(listOf("Crime", "Drama"), first.genres)
        assertEquals(6.1, page.items[1].rating, "rating given as a string")
        assertNull(page.nextSkip, "no paging")

        val calls = requested.size
        assertTrue((p.page(catalog, skip = 100) as AppResult.Ok).value.items.isEmpty())
        assertEquals(calls, requested.size, "skip is not sent to catalogs that do not support it")
        assertIs<AppError.InvalidState>((p.search("leo", null) as AppResult.Err).error, "no search catalog")
        assertIs<AppError.InvalidState>((p.metadata(first.ref) as AppResult.Err).error, "no meta resource")
    }

    @Test
    fun indianRegionalCatalogsArePerLanguage() = runTest {
        val info = (regional().describe() as AppResult.Ok).value
        assertEquals(listOf("catalog", "meta"), info.resources.filter { it != "stream" })
        assertTrue("stream" in info.resources, "advertised, but never used")
        val byId = info.catalogs.associateBy { it.id }
        listOf("te", "hi", "ta", "ml", "kn", "bn", "mr", "gu", "pa").forEach { code ->
            assertEquals(listOf(code), byId.getValue(code).languages)
            assertEquals(CatalogType.MOVIE, byId.getValue(code).type)
            assertEquals(listOf(code), byId.getValue("${code}_series").languages)
            assertEquals(CatalogType.SERIES, byId.getValue("${code}_series").type)
        }
        assertEquals(listOf("hi"), byId.getValue("hindi_dubbed").languages)
        assertEquals(listOf("dubbed"), byId.getValue("hindi_dubbed").tags)
        assertTrue(byId.getValue("ott_releases").languages.isEmpty())
        assertEquals(setOf("te", "hi", "ta", "ml", "kn", "bn", "mr", "gu", "pa"), info.languages.toSet())
        assertEquals(listOf("tt", "tmdb:", "ir"), info.metaIdPrefixes)
    }

    @Test
    fun compositeIdsAndPaging() = runTest {
        val p = regional()
        val ta = (p.describe() as AppResult.Ok).value.catalogs.first { it.id == "ta" }
        val page = (p.page(ta) as AppResult.Ok).value
        val composite = page.items.first()
        assertEquals("1900001", composite.ref.tmdbId)
        assertNull(composite.ref.imdbId)
        assertEquals("Sample Tamil Film", composite.title)
        assertEquals(2026, composite.year)
        assertEquals("A young filmmaker chases a dream.", composite.description, "decoded from the id")
        assertEquals(listOf("ta"), composite.languages)
        assertEquals("tt2000002", page.items[1].ref.imdbId)
        assertEquals(2, page.items.size, "the add-on's promo entry is not a title")
        assertEquals(3, page.nextSkip, "skip advances by entries received, dropped ones included")
        assertTrue((p.page(ta, skip = 20) as AppResult.Ok).value.items.isNotEmpty())
        assertTrue(requested.any { it.endsWith("/catalog/movie/ta/skip=20.json") })
    }

    @Test
    fun metadataForCompositeIdsAndNotFoundForEmptyArrays() = runTest {
        val p = regional()
        val ta = (p.describe() as AppResult.Ok).value.catalogs.first { it.id == "ta" }
        val composite = (p.page(ta) as AppResult.Ok).value.items.first()
        val meta = (p.metadata(composite.ref) as AppResult.Ok).value
        assertEquals("Sample Tamil Film", meta.title)
        assertEquals(2026, meta.year)
        // The live add-on answers [] for IMDb ids (verified): treated as not found.
        assertIs<AppError.NotFound>((p.metadata(CatalogItemRef(p.id, CatalogType.MOVIE, "tt2000002", "tt2000002")) as AppResult.Err).error)
    }

    @Test
    fun mirrorFailover() = runTest {
        val p = regional(down = setOf("indian-regional-catalog.vercel.app"))
        val info = (p.describe() as AppResult.Ok).value
        assertEquals("https://83e20802dcf1-indian-streams.baby-beamup.club", info.baseUrl)
        val ta = info.catalogs.first { it.id == "ta" }
        assertTrue((p.page(ta) as AppResult.Ok).value.items.isNotEmpty())
        assertTrue(requested.last().contains("baby-beamup.club"), "the working mirror is remembered")
    }

    @Test
    fun streamsAreNeverRequested() = runTest {
        val p = regional()
        val info = (p.describe() as AppResult.Ok).value
        info.catalogs.take(3).forEach { p.page(it) }
        p.metadata(CatalogItemRef(p.id, CatalogType.MOVIE, "tt2000002", "tt2000002"))
        assertTrue(requested.none { "/stream/" in it || "/subtitles/" in it }, "$requested")
    }

    @Test
    fun idParsing() {
        assertEquals("tt0903747", StremioIds.parse("tt0903747").imdbId)
        assertEquals("123", StremioIds.parse("tmdb:123").tmdbId)
        val c = StremioIds.parse("tmdb:9||Film||2020||movie||https://img/x.jpg||A%20b%2C%20c")
        assertEquals(listOf("9", "Film", "2020", "https://img/x.jpg", "A b, c"), listOf(c.tmdbId, c.title, c.year.toString(), c.posterUrl, c.description))
        assertNull(StremioIds.parse("kitsu:42").imdbId)
    }

    @Test
    fun definitionsAreConfigurable() {
        assertEquals(listOf("indiastreams", "indian_regional"), IndianCatalogs.KNOWN.keys.toList())
        assertEquals(1, IndianCatalogs.create(listOf("indian_regional", "unknown"), { HttpClients.create(MockEngine { respond("") }) }).size)
        val custom = StremioAddonDefinition.custom("stremio://example.org/addon/manifest.json", "Mine")
        assertEquals("https://example.org/addon/manifest.json", custom.manifestUrls.single())
    }
}
