package dev.yourstream.desktop

import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.network.HttpClients
import dev.yourstream.desktop.addons.AddonService
import dev.yourstream.desktop.data.SettingsStore
import dev.yourstream.desktop.library.PosterService
import dev.yourstream.desktop.playback.VideoPlayerController
import dev.yourstream.desktop.playback.VlcEngine
import dev.yourstream.desktop.ui.CatalogTitleScreen
import dev.yourstream.desktop.ui.DiscoverScreen
import dev.yourstream.desktop.ui.YourStreamTheme
import dev.yourstream.desktop.ui.streamRecord
import dev.yourstream.metadata.core.InMemoryMetadataCache
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stremio / Nuvio add-ons on the desktop, against a small add-on served on 127.0.0.1 (the add-on
 * rules allow plain http only on the local network): install, catalogs, details, streams, and a
 * stream played by real VLC.
 */
@OptIn(ExperimentalTestApi::class)
class AddonsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun json(path: String, body: () -> String) = server.createContext(path) { ex ->
        val b = body().toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    init {
        json("/addon/manifest.json") {
            """{"id":"test.addon","version":"1.2.0","name":"Test Add-on","description":"For tests","resources":["catalog","meta","stream"],
               "types":["movie","series"],"idPrefixes":["tt"],"catalogs":[{"type":"movie","id":"top","name":"Top Movies"}]}"""
        }
        json("/addon/catalog/movie/top.json") {
            """{"metas":[{"id":"tt0000001","type":"movie","name":"Alpha Movie","releaseInfo":"2021","imdbRating":"7.4"},
                         {"id":"tt0000002","type":"movie","name":"Beta Movie","releaseInfo":"2019"}]}"""
        }
        json("/addon/meta/movie/tt0000001.json") {
            """{"meta":{"id":"tt0000001","type":"movie","name":"Alpha Movie","releaseInfo":"2021","description":"A test film.","genres":["Drama"],"cast":["Someone"]}}"""
        }
        json("/addon/stream/movie/tt0000001.json") {
            """{"streams":[
                 {"name":"Test\n1080p","title":"Alpha.Movie.2021.1080p.mkv","url":"${base()}/video/clip.mkv","behaviorHints":{"proxyHeaders":{"request":{"User-Agent":"TestAgent"}}}},
                 {"name":"Test\n4K","title":"Alpha 2160p torrent","infoHash":"0123456789abcdef0123456789abcdef01234567"}]}"""
        }
        server.createContext("/video/clip.mkv") { ex ->
            val b = AddonsTest::class.java.getResourceAsStream("/clip-30s.mkv")!!.readBytes()
            ex.responseHeaders.add("Content-Type", "video/x-matroska")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun service(): AddonService {
        val settings = SettingsStore(File(tmp.newFolder(), "settings.json"))
        settings.update { it.copy(addons = emptyList()) } // no Cinemeta: tests stay offline
        return AddonService(HttpClients.create(), settings, NoOpLogger)
    }

    private val posters by lazy {
        PosterService(MetadataMatcher(MetadataProviderManager(emptyList(), InMemoryMetadataCache(), Clock.System, NoOpLogger)), HttpClients.create(), tmp.newFolder("p"), NoOpLogger)
    }

    @Test
    fun links() {
        assertEquals("https://x.example/a/manifest.json", AddonService.normalize("stremio://x.example/a/manifest.json"))
        assertEquals("http://192.168.1.5:7000/manifest.json", AddonService.normalize(" http://192.168.1.5:7000/manifest.json "))
        assertNull(AddonService.normalize("http://example.com/manifest.json"), "plain http only on the local network")
        assertNull(AddonService.normalize("https://example.com/addon"))
    }

    @Test
    fun installCatalogsDetailsAndStreams(): Unit = runBlocking {
        val s = service()
        val installed = assertIs<AppResult.Ok<*>>(s.install("${base()}/addon/manifest.json"))
        assertEquals("Test Add-on", s.addons.value.single().name)
        assertIs<AppResult.Err>(s.install("${base()}/addon/manifest.json"), "twice is refused")
        val catalog = s.catalogs(CatalogType.MOVIE).single()
        assertEquals("Top Movies", catalog.name)
        val page = assertIs<AppResult.Ok<dev.yourstream.catalog.api.CatalogPage>>(s.page(catalog))
        assertEquals(listOf("Alpha Movie", "Beta Movie"), page.value.items.map { it.title })
        val meta = s.metadata(page.value.items.first())
        assertEquals("A test film.", meta.description)
        val groups = s.streams(CatalogType.MOVIE, "tt0000001").toList()
        val streams = groups.single().streams
        assertEquals(2, streams.size)
        assertTrue(streams[0].playable)
        assertTrue(streams[1].isTorrent)
        val record = streamRecord(meta, null, streams[0])
        assertEquals(1080, record.resolution)
        s.remove(s.addons.value.single().url)
        assertTrue(s.addons.value.isEmpty())
        installed.value
    }

    @Test
    fun discoverAndTitlePageWithStreams() {
        val s = service()
        runBlocking { s.install("${base()}/addon/manifest.json") }
        var opened: dev.yourstream.catalog.api.CatalogItem? = null
        var played: AddonStream? = null
        runDesktopComposeUiTest(1280, 820) {
            setContent { YourStreamTheme(true, manga = true) { Surface { DiscoverScreen(s, posters) { opened = it } } } }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Alpha Movie")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Beta Movie").assertExists()
            onNodeWithText("Alpha Movie").performClick()
        }
        val item = opened!!
        runDesktopComposeUiTest(1280, 900) {
            setContent { YourStreamTheme(true, manga = false) { Surface { CatalogTitleScreen(item, s, posters, emptyList(), {}, {}) { _, _, st -> played = st } } } }
            // The streams are below the details, in a lazy list: scroll to them as a user would
            // (how far down they sit depends on the platform's fonts).
            onNodeWithText("A test film.").assertExists()
            val list = onNode(hasScrollAction())
            waitUntil(timeoutMillis = 30_000) { runCatching { list.performScrollToNode(hasText("Test 1080p")) }.isSuccess }
            list.performScrollToNode(hasText("Test 4K"))
            onNodeWithText("Test 4K").assertExists() // the torrent, listed but not playable here
            list.performScrollToNode(hasText("Test 1080p"))
            onNodeWithText("Test 1080p").performClick()
        }
        assertEquals("Test\n1080p", played?.name)
        assertEquals(mapOf("User-Agent" to "TestAgent"), played?.headers)
    }

    @Test
    fun anAddonStreamPlaysInVlc() {
        val engine = VlcEngine(NoOpLogger)
        val status = runBlocking { engine.start() }
        assumeTrue("VLC not installed", status is VlcEngine.Status.Ready)
        val p = VideoPlayerController((status as VlcEngine.Status.Ready).factory, NoOpLogger)
        try {
            p.play("${base()}/video/clip.mkv", extraOptions = listOf(":http-user-agent=TestAgent"))
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end && p.state.value.timeMs < 1_500) Thread.sleep(50)
            assertTrue(p.state.value.timeMs >= 1_500 && p.frames.frameId.longValue > 0, "playing: ${p.state.value}")
        } finally {
            p.close()
            engine.close()
        }
    }
}
