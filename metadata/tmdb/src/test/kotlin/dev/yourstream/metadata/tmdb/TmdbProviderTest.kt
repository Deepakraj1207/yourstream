package dev.yourstream.metadata.tmdb

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.network.HttpClients
import dev.yourstream.metadata.api.MetadataQuery
import dev.yourstream.metadata.api.MetadataType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TmdbProviderTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun provider(config: TmdbConfig = TmdbConfig(apiKey = "k123"), handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): TmdbProvider {
        val engine = MockEngine { req ->
            requests += req
            val (status, body) = handler(req)
            respond(body, status, if (status == HttpStatusCode.TooManyRequests) headersOf("Retry-After", "7") else json)
        }
        return TmdbProvider(HttpClients.create(engine), config)
    }

    @Test
    fun aBlockedDomainFallsBackToTmdbsSecondDomain() = runTest {
        val engine = MockEngine { req ->
            requests += req
            if (req.url.host == "api.themoviedb.org") throw java.io.IOException("Unable to resolve host \"api.themoviedb.org\"")
            respond("""{"success":true,"status_code":1,"results":[{"id":603,"title":"The Matrix","release_date":"1999-03-30"}]}""", HttpStatusCode.OK, json)
        }
        val p = TmdbProvider(HttpClients.create(engine), TmdbConfig(apiKey = "k123"))
        assertEquals(AppResult.Ok(Unit), p.check(TmdbConfig(apiKey = "k123")))
        assertEquals(listOf("api.themoviedb.org", "api.tmdb.org"), requests.map { it.url.host })
        requests.clear()
        p.search(MetadataQuery("The Matrix", 1999, MetadataType.MOVIE))
        assertEquals("api.tmdb.org", requests.first().url.host, "the domain that worked is asked first")
        assertEquals("/3/search/movie", requests.first().url.encodedPath)
    }

    @Test
    fun aRejectedKeyIsNotRetriedElsewhere() = runTest {
        val p = provider { HttpStatusCode.Unauthorized to """{"status_code":7,"status_message":"Invalid API key"}""" }
        val r = p.check(TmdbConfig(apiKey = "bad"))
        assertTrue(r is AppResult.Err && r.error is dev.yourstream.core.common.AppError.NotConfigured)
        assertEquals(1, requests.size)
    }

    @Test
    fun searchMapsResultsAndSendsTitleYearKey() = runTest {
        val p = provider {
            HttpStatusCode.OK to """{"page":1,"results":[
              {"id":603,"title":"The Matrix","original_title":"The Matrix","release_date":"1999-03-30","overview":"Neo…",
               "poster_path":"/p.jpg","backdrop_path":"/b.jpg","original_language":"en","popularity":80.5,"vote_average":8.2,"extra":1}]}"""
        }
        val r = (p.search(MetadataQuery("The Matrix", 1999, MetadataType.MOVIE, "en")) as AppResult.Ok).value
        val c = r.single()
        assertEquals("603", c.ref.id)
        assertEquals(1999, c.year)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", c.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", c.backdropUrl)
        val url = requests.single().url
        assertEquals("/3/search/movie", url.encodedPath)
        assertEquals("The Matrix", url.parameters["query"])
        assertEquals("1999", url.parameters["year"])
        assertEquals("k123", url.parameters["api_key"])
    }

    @Test
    fun seriesSearchUsesFirstAirDateYear() = runTest {
        val p = provider { HttpStatusCode.OK to """{"results":[{"id":57911,"name":"Doraemon","first_air_date":"2005-04-15","original_language":"ja"}]}""" }
        val c = (p.search(MetadataQuery("Doraemon", 2005, MetadataType.SERIES)) as AppResult.Ok).value.single()
        assertEquals(MetadataType.SERIES, c.ref.type)
        assertEquals("Doraemon", c.title)
        assertEquals("2005", requests.single().url.parameters["first_air_date_year"])
    }

    @Test
    fun movieDetailsIncludeGenresCastDirectorsAndImdb() = runTest {
        val p = provider {
            HttpStatusCode.OK to """{"id":27205,"title":"Inception","original_title":"Inception","overview":"Dreams","genres":[{"id":1,"name":"Action"},{"id":2,"name":"Sci-Fi"}],
              "release_date":"2010-07-15","original_language":"en","spoken_languages":[{"iso_639_1":"en"},{"iso_639_1":"ja"}],"runtime":148,
              "imdb_id":"tt1375666","credits":{"cast":[{"name":"B","order":1},{"name":"A","character":"Cobb","order":0,"profile_path":"/a.jpg"}],
              "crew":[{"name":"Christopher Nolan","job":"Director"},{"name":"X","job":"Writer"}]}}"""
        }
        val m = (p.movie("27205", "en") as AppResult.Ok).value
        assertEquals(listOf("Action", "Sci-Fi"), m.genres)
        assertEquals("tt1375666", m.ref.imdbId)
        assertEquals(listOf("A", "B"), m.cast.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w185/a.jpg", m.cast.first().profileUrl)
        assertEquals(listOf("Christopher Nolan"), m.directors)
        assertEquals(148, m.runtimeMinutes)
        assertEquals(listOf("en", "ja"), m.spokenLanguages)
        assertEquals("credits", requests.single().url.parameters["append_to_response"])
    }

    @Test
    fun seriesSeasonAndEpisode() = runTest {
        val p = provider { req ->
            when {
                req.url.encodedPath.endsWith("/episode/15") -> HttpStatusCode.OK to """{"season_number":2,"episode_number":15,"name":"E15","still_path":"/s.jpg"}"""
                req.url.encodedPath.endsWith("/season/2") -> HttpStatusCode.OK to """{"season_number":2,"name":"Season 2","episodes":[{"season_number":2,"episode_number":1,"name":"Pilot"}]}"""
                else -> HttpStatusCode.OK to """{"id":57911,"name":"Doraemon","first_air_date":"2005-04-15","seasons":[{"season_number":1,"episode_count":50}],"external_ids":{"imdb_id":"tt0"}}"""
            }
        }
        val s = (p.series("57911", null) as AppResult.Ok).value
        assertEquals(2005, s.year); assertEquals("tt0", s.ref.imdbId); assertEquals(50, s.seasons.single().episodeCount)
        assertEquals("Pilot", (p.season("57911", 2, null) as AppResult.Ok).value.episodes.single().title)
        val e = (p.episode("57911", 2, 15, null) as AppResult.Ok).value
        assertEquals("E15", e.title); assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", e.stillUrl)
    }

    @Test
    fun httpErrorsMapToAppErrors() = runTest {
        assertEquals(AppError.RateLimited(7), (provider { HttpStatusCode.TooManyRequests to "" }.search(MetadataQuery("x")) as AppResult.Err).error)
        assertIs<AppError.NotConfigured>((provider { HttpStatusCode.Unauthorized to """{"status_code":7}""" }.movie("1", null) as AppResult.Err).error)
        assertIs<AppError.NotFound>((provider { HttpStatusCode.NotFound to "{}" }.movie("1", null) as AppResult.Err).error)
        val server = (provider { HttpStatusCode.ServiceUnavailable to "" }.movie("1", null) as AppResult.Err).error
        assertIs<AppError.Remote>(server); assertTrue(server.isServerError)
        assertIs<AppError.Unknown>((provider { HttpStatusCode.OK to "not json" }.movie("1", null) as AppResult.Err).error)
    }

    @Test
    fun changedResponseFormatsAreErrorsNotCrashes() = runTest {
        // The provider changes its JSON: wrong types, an HTML error page, an empty body, a truncated answer.
        for (body in listOf("""{"results":"maintenance"}""", """{"results":[{"id":"not-a-number","title":7}]}""",
            "<html><body>502 Bad Gateway</body></html>", "", """{"id":1,"title":"Leo",""")) {
            val p = provider { HttpStatusCode.OK to body }
            assertTrue(p.search(MetadataQuery("leo")) is AppResult.Err, "search with $body")
            assertTrue(p.movie("1", null) is AppResult.Err || p.movie("1", null) is AppResult.Ok, "movie with $body never throws")
        }
    }

    @Test
    fun withoutKeysNothingIsSent() = runTest {
        val p = provider(TmdbConfig()) { HttpStatusCode.OK to "{}" }
        assertFalse(p.isConfigured)
        assertIs<AppError.NotConfigured>((p.search(MetadataQuery("x")) as AppResult.Err).error)
        assertTrue(requests.isEmpty())
        assertFalse(TmdbConfig(apiKey = "secret").toString().contains("secret"))
    }

    @Test
    fun readTokenIsSentAsBearer() = runTest {
        val p = provider(TmdbConfig(readToken = "tok")) { HttpStatusCode.OK to """{"results":[]}""" }
        p.search(MetadataQuery("x", type = MetadataType.MOVIE))
        assertEquals("Bearer tok", requests.single().headers["Authorization"])
        assertEquals(null, requests.single().url.parameters["api_key"])
    }

    @Test
    fun findByImdb() = runTest {
        val p = provider { HttpStatusCode.OK to """{"movie_results":[{"id":27205,"title":"Inception"}],"tv_results":[]}""" }
        assertEquals("27205", (p.findByImdbId("tt1375666", MetadataType.MOVIE) as AppResult.Ok).value.id)
        assertIs<AppError.NotFound>((p.findByImdbId("tt1375666", MetadataType.SERIES) as AppResult.Err).error)
    }

    /** The title page's details: found by IMDb id, then one request with everything appended. */
    @Test
    fun titleDetailsForTheTitlePage() = runTest {
        val p = provider { req ->
            when (req.url.encodedPath) {
                "/3/find/tt9000001" -> HttpStatusCode.OK to """{"movie_results":[{"id":555}],"tv_results":[]}"""
                "/3/movie/555" -> HttpStatusCode.OK to """{"id":555,"title":"Baththa","overview":"In 1986 Madras…","genres":[{"name":"Drama"},{"name":"Comedy"}],
                  "runtime":159,"release_date":"2026-01-10","status":"Released","vote_average":7.9,"vote_count":120,"backdrop_path":"/b.jpg","poster_path":"/p.jpg",
                  "imdb_id":"tt9000001","original_language":"ta","origin_country":["IN"],
                  "production_companies":[{"name":"Star Studios","logo_path":"/star.png"},{"name":"Small Films"}],
                  "credits":{"cast":[{"name":"Vijay Sethupathi","character":"Bathvachalam","profile_path":"/v.jpg","order":1},{"name":"Sai Prithviraj","character":"Sabari","order":0}],
                    "crew":[{"name":"Balaji T","job":"Director","department":"Directing"},{"name":"Balaji T","job":"Screenplay","department":"Writing"},{"name":"Someone","job":"Editor","department":"Editing"}]},
                  "videos":{"results":[{"name":"Teaser","key":"t1","site":"YouTube","type":"Teaser"},{"name":"Official Trailer","key":"tr1","site":"YouTube","type":"Trailer","official":true,"published_at":"2026-01-01T00:00:00Z"},{"name":"Vimeo","key":"v","site":"Vimeo","type":"Trailer"}]},
                  "images":{"logos":[{"file_path":"/de.png","iso_639_1":"de","vote_average":9},{"file_path":"/en.png","iso_639_1":"en","vote_average":5},{"file_path":"/ta.png","iso_639_1":"ta","vote_average":1}],
                    "posters":[{"file_path":"/titled.jpg","iso_639_1":"en","vote_average":9},{"file_path":"/clean.jpg","iso_639_1":null,"vote_average":4}]},
                  "recommendations":{"results":[{"id":7,"title":"Airborne","release_date":"1993-09-17","poster_path":"/a.jpg"}]},
                  "release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"R"}]},{"iso_3166_1":"IN","release_dates":[{"certification":""},{"certification":"U/A 13+"}]}]}}"""
                else -> HttpStatusCode.NotFound to "{}"
            }
        }
        val d = (p.details(MetadataType.MOVIE, "tt9000001", null, "ta-IN", "IN") as AppResult.Ok).value
        assertEquals("555", d.tmdbId)
        assertEquals("U/A 13+", d.certification, "the user's region first")
        assertEquals("https://image.tmdb.org/t/p/w500/ta.png", d.logoUrl, "the logo in the user's language first")
        assertEquals("https://image.tmdb.org/t/p/w780/clean.jpg", d.textlessPosterUrl, "a poster without printed title")
        assertEquals(listOf("Balaji T"), d.directors)
        assertEquals(listOf("Balaji T"), d.writers)
        assertEquals(listOf("Sai Prithviraj", "Vijay Sethupathi"), d.cast.map { it.name }, "billing order")
        assertEquals(listOf("tr1", "t1"), d.videos.map { it.youtubeKey }, "YouTube only, trailers first")
        assertEquals(listOf("Star Studios", "Small Films"), d.companies.map { it.name })
        assertEquals(null, d.companies[1].logoUrl)
        assertEquals(listOf("IN"), d.originCountries)
        assertEquals(159, d.runtimeMinutes)
        assertEquals(listOf("Airborne" to 1993), d.recommendations.map { it.title to it.year })
        val asked = requests.last().url.parameters
        assertTrue(asked["append_to_response"]!!.contains("release_dates"))
        assertEquals("ta,en,null", asked["include_image_language"])
        // A second visit is not asked again.
        val before = requests.size
        p.details(MetadataType.MOVIE, null, "555", "ta-IN", "IN")
        assertEquals(before, requests.size)
        // By IMDb id for another region: the page is fetched again, but the IMDb → TMDB id is
        // remembered, so there is no second /find.
        val before2 = requests.size
        p.details(MetadataType.MOVIE, "tt9000001", null, "ta-IN", "GB")
        assertEquals(listOf("/3/movie/555"), requests.drop(before2).map { it.url.encodedPath })
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", d.backdropUrl)
        // Elsewhere: the US rating.
        assertEquals("R", (p.details(MetadataType.MOVIE, null, "555", "ta-IN", "DE") as AppResult.Ok).value.certification)
    }
}
