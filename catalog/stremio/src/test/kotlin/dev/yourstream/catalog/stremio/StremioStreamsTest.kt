package dev.yourstream.catalog.stremio

import dev.yourstream.catalog.api.AddonLinks
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogType
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

class StremioStreamsTest {
    private val requested = mutableListOf<String>()

    private fun provider(routes: Map<String, String>, url: String = "https://addon.example.com/manifest.json"): StremioCatalogProvider {
        val engine = MockEngine { req ->
            val u = req.url.toString()
            requested += u
            routes.entries.firstOrNull { u.endsWith(it.key) }?.let { respond(it.value, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) }
                ?: respond("Not found", HttpStatusCode.NotFound)
        }
        return StremioCatalogProvider(HttpClients.create(engine), StremioAddonDefinition.custom(url, "Test"), requestsPerSecond = 1000)
    }

    @Test
    fun torrentStreamsKeepWhatTheEngineNeeds() = runTest {
        val p = provider(mapOf(
            "/manifest.json" to """{"id":"t","name":"Torrents","resources":["stream"],"types":["movie","series"],"idPrefixes":["tt"]}""",
            "/stream/movie/tt0111161.json" to """{"streams":[
                {"name":"T\n1080p","title":"Film.1080p.mkv\n👤 52","infoHash":"AABBCCDDEEFF00112233445566778899AABBCCDD","fileIdx":2,
                 "sources":["tracker:udp://tracker.example.org:1337/announce","dht:aabbccddeeff00112233445566778899aabbccdd"],
                 "behaviorHints":{"bingeGroup":"x","filename":"Film.1080p.mkv","videoSize":1500000000}},
                {"name":"Magnet","url":"magnet:?xt=urn:btih:VKZ4Z3HG3U4Y5RPXH5C3F7ANNEAX6Z2N&tr=udp%3A%2F%2Fopen.example.net%3A6969&dn=Film"},
                {"name":"Direct","url":"https://cdn.example.com/film.mp4","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://example.com"}}}},
                {"name":"Nothing"}
            ]}""",
        ))
        val streams = (p.streams(CatalogType.MOVIE, "tt0111161") as AppResult.Ok).value
        assertEquals(3, streams.size, "a stream with nothing to play is dropped")

        val t = streams[0]
        assertTrue(t.isTorrent)
        assertFalse(t.playable)
        assertEquals("aabbccddeeff00112233445566778899aabbccdd", t.infoHash, "hashes are lowercased")
        assertEquals(2, t.fileIdx)
        assertEquals(listOf("tracker:udp://tracker.example.org:1337/announce", "dht:aabbccddeeff00112233445566778899aabbccdd"), t.sources)
        assertEquals("Film.1080p.mkv", t.fileName)
        assertEquals(1_500_000_000L, t.sizeBytes)

        val m = streams[1]
        assertTrue(m.isTorrent, "magnet links become torrents")
        assertNull(m.url)
        assertEquals(40, m.infoHash!!.length)
        assertEquals(listOf("tracker:udp://open.example.net:6969"), m.sources)

        val d = streams[2]
        assertTrue(d.playable)
        assertFalse(d.isTorrent)
        assertEquals(mapOf("Referer" to "https://example.com"), d.headers)
    }

    @Test
    fun streamRequestsFollowTheManifest() = runTest {
        val p = provider(mapOf(
            "/manifest.json" to """{"id":"a","name":"Anime","resources":["catalog",{"name":"stream","types":["series"],"idPrefixes":["kitsu:"]}],"types":["movie","series"],"catalogs":[]}""",
        ))
        assertTrue(p.hasStreams())
        assertTrue(p.handles(CatalogType.SERIES, "kitsu:123:4"))
        assertFalse(p.handles(CatalogType.SERIES, "tt0903747:1:2"), "only its own id prefixes")
        assertFalse(p.handles(CatalogType.MOVIE, "kitsu:9"), "only its own types")

        val open = provider(mapOf("/manifest.json" to """{"id":"o","name":"Open","resources":["stream"]}"""), "https://open.example.com/manifest.json")
        assertTrue(open.handles(CatalogType.MOVIE, "anything:1"), "no types or prefixes: asked for everything")

        val none = provider(mapOf("/manifest.json" to """{"id":"c","name":"Catalogs","resources":["catalog","meta"],"catalogs":[]}"""), "https://c.example.com/manifest.json")
        assertFalse(none.hasStreams())
        assertFalse(none.handles(CatalogType.MOVIE, "tt1"))
    }

    @Test
    fun metaWithoutIdPrefixesAnswersForAnyId() = runTest {
        val p = provider(mapOf(
            "/manifest.json" to """{"id":"m","name":"Meta","resources":["catalog","meta"],"types":["movie"],"catalogs":[]}""",
            "/meta/movie/tmdb%3A550.json" to """{"meta":{"id":"tmdb:550","type":"movie","name":"Fight Club","releaseInfo":"1999"}}""",
        ))
        val meta = p.metadata(CatalogItemRef(CatalogProviderId("x"), CatalogType.MOVIE, "tmdb:550"))
        assertEquals("Fight Club", (meta as AppResult.Ok).value.title)
        assertEquals(1999, meta.value.year)
    }

    @Test
    fun nuvioPluginRepositoriesAreNamedAsSuch() = runTest {
        val p = provider(mapOf("/manifest.json" to """{"name":"Some Plugins","version":"1.0.0","scrapers":[{"id":"x","name":"X","filename":"providers/x.js"}]}"""))
        val r = p.describe()
        val e = assertIs<AppError.InvalidInput>((r as AppResult.Err).error)
        assertEquals("NUVIO_PLUGINS", e.reason)
        assertTrue("plugin" in e.message)
    }

    @Test
    fun localNetworkAddonsMayUsePlainHttp() = runTest {
        assertTrue(AddonLinks.isLocalHttp("http://192.168.1.20:7000/manifest.json"))
        assertTrue(AddonLinks.isLocalHttp("http://user:pw@10.0.0.5/manifest.json"))
        assertTrue(AddonLinks.isLocalHttp("http://[::1]:7000/manifest.json"))
        assertTrue(AddonLinks.isLocalHttp("http://100.101.102.103/manifest.json"), "Tailscale")
        assertFalse(AddonLinks.isLocalHttp("http://172.32.0.1/manifest.json"))
        assertFalse(AddonLinks.isLocalHttp("http://addon.example.com/manifest.json"))
        assertFalse(AddonLinks.isLocalHttp("https://192.168.1.20/manifest.json"), "https needs no exception")
        val p = provider(mapOf("/manifest.json" to """{"id":"l","name":"Local","resources":["stream"]}"""), "http://192.168.1.20:7000/manifest.json")
        assertTrue(p.hasStreams())
        assertEquals("http://192.168.1.20:7000/manifest.json", requested.last())
    }

    @Test
    fun magnetLinks() {
        val hex = Magnets.parse("magnet:?xt=urn:btih:AABBCCDDEEFF00112233445566778899AABBCCDD&dn=A%20Film&tr=udp%3A%2F%2Fa%3A1&tr=udp%3A%2F%2Fa%3A1")!!
        assertEquals("aabbccddeeff00112233445566778899aabbccdd", hex.infoHash)
        assertEquals("A Film", hex.name)
        assertEquals(listOf("udp://a:1"), hex.trackers)
        // Base32 form of the same kind of hash: 32 characters → 40 hex digits.
        val b32 = Magnets.parse("magnet:?xt=urn:btih:VKZ4Z3HG3U4Y5RPXH5C3F7ANNEAX6Z2N")!!
        assertEquals("aab3ccece6dd398ec5f73f45b2fc0d69017f674d", b32.infoHash)
        assertNull(Magnets.parse("magnet:?xt=urn:sha1:abc"))
        assertNull(Magnets.parse("https://example.com"))
    }
}
