package dev.yourstream.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.core.common.Clock
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.core.network.HttpClients
import dev.yourstream.desktop.addons.AddonService
import dev.yourstream.desktop.data.LibraryBuilder
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.data.SettingsStore
import dev.yourstream.desktop.library.PosterService
import dev.yourstream.desktop.ui.CatalogTitleScreen
import dev.yourstream.desktop.ui.HomeCache
import dev.yourstream.desktop.ui.HomeScreen
import dev.yourstream.desktop.ui.LocalNavInset
import dev.yourstream.desktop.ui.NavDestination
import dev.yourstream.desktop.ui.SearchScreen
import dev.yourstream.desktop.ui.SettingsScreen
import dev.yourstream.desktop.ui.SettingsSection
import dev.yourstream.desktop.ui.SideBar
import dev.yourstream.desktop.ui.YourStreamTheme
import dev.yourstream.metadata.core.InMemoryMetadataCache
import dev.yourstream.metadata.core.MetadataProviderManager
import dev.yourstream.metadata.core.match.MetadataMatcher
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home and Search in the window's shell (the floating sidebar), against a small add-on on
 * 127.0.0.1 with generated artwork, and a sample Telegram library: the hero, the rows, "See all",
 * the Telegram badge on add-on titles you have, Continue watching; search across both; an add-on
 * title page with episodes. Screenshots go to build/screenshots for review.
 */
@OptIn(ExperimentalTestApi::class)
class HomeAndSearchTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private fun base() = "http://127.0.0.1:${server.address.port}"

    private val movies = listOf(
        Triple("Midnight Harbor", 2024, 0xFF1D3557), Triple("The Last Orbit", 2023, 0xFF6A040F), Triple("Paper Kingdoms", 2022, 0xFF2D6A4F),
        Triple("Echo Valley", 2021, 0xFF7B2CBF), Triple("Silent Tide", 2024, 0xFF0B525B), Triple("Neon Monsoon", 2025, 0xFFB5179E),
        Triple("Glass Garden", 2020, 0xFF386641), Triple("Northern Lights", 2019, 0xFF023E8A), Triple("Iron Meadow", 2022, 0xFF9C6644),
        Triple("Velvet Storm", 2023, 0xFF5A189A),
    )
    private val series = listOf(
        Triple("Signal Lost", 2022, 0xFF264653), Triple("Harbor Lights", 2021, 0xFFE76F51), Triple("Kitchen Wars", 2020, 0xFFBC6C25),
        Triple("The Archive", 2024, 0xFF3A0CA3), Triple("Paper Moon", 2019, 0xFF6D6875), Triple("Dust and Gold", 2023, 0xFF8D6346),
    )

    private fun id(title: String) = "tt" + (title.hashCode().toLong() and 0xFFFFFF).toString().padStart(7, '0')

    private fun meta(t: Triple<String, Int, Long>, type: String) = """{"id":"${id(t.first)}","type":"$type","name":"${t.first}","releaseInfo":"${t.second}",
        "poster":"${base()}/img/p/${id(t.first)}.png","background":"${base()}/img/b/${id(t.first)}.png","imdbRating":"${7.0 + (t.first.length % 20) / 10.0}",
        "runtime":"${100 + t.first.length * 2} min","genres":["Drama","Mystery"],
        "description":"A sample title made for the desktop app's tests: ${t.first.lowercase()}, with generated artwork in place of a real poster."}"""

    private val art = HashMap<String, ByteArray>()

    init {
        val all = movies.map { it to "movie" } + series.map { it to "series" }
        all.forEach { (t, _) ->
            art["/img/p/${id(t.first)}.png"] = picture(300, 450, t.first, t.third.toInt())
            art["/img/b/${id(t.first)}.png"] = picture(1280, 720, t.first, t.third.toInt())
        }
        for (ep in 1..6) art["/img/e/$ep.png"] = picture(480, 270, "Episode $ep", (0xFF264653 + ep * 0x101010).toInt())
        server.createContext("/") { ex ->
            val path = ex.requestURI.rawPath
            val body: ByteArray? = when {
                path.startsWith("/img/") -> art[path]
                path == "/addon/manifest.json" -> """{"id":"sample.addon","version":"1.0.0","name":"Sample Add-on","resources":["catalog","meta","stream"],"types":["movie","series"],"idPrefixes":["tt"],
                    "catalogs":[{"type":"movie","id":"popular","name":"Popular","extra":[{"name":"search"}]},{"type":"series","id":"popular","name":"Popular","extra":[{"name":"search"}]}]}""".toByteArray()
                path.startsWith("/addon/catalog/") -> {
                    val type = path.removePrefix("/addon/catalog/").substringBefore('/')
                    val list = if (type == "movie") movies else series
                    val q = Regex("search=([^/.]+)").find(java.net.URLDecoder.decode(path, "UTF-8"))?.groupValues?.get(1)?.lowercase()
                    val shown = if (q == null) list else list.filter { q in it.first.lowercase() }
                    """{"metas":[${shown.joinToString(",") { meta(it, type) }}]}""".toByteArray()
                }
                path.startsWith("/addon/meta/") -> {
                    val type = path.removePrefix("/addon/meta/").substringBefore('/')
                    val mid = path.substringAfterLast('/').removeSuffix(".json")
                    val t = (if (type == "movie") movies else series).first { id(it.first) == mid }
                    val videos = if (type == "series") (1..6).joinToString(",", prefix = ""","videos":[""", postfix = "]") { e ->
                        """{"id":"$mid:1:$e","season":1,"episode":$e,"title":"Chapter $e","thumbnail":"${base()}/img/e/$e.png","overview":"What happens in chapter $e."}"""
                    } else ""
                    """{"meta":${meta(t, type).dropLast(1)}$videos}}""".toByteArray()
                }
                path.startsWith("/addon/stream/") -> """{"streams":[{"name":"Sample\n2160p","title":"Sample 2160p HDR","url":"${base()}/v.mkv"},{"name":"Sample\n1080p","title":"Sample 1080p","url":"${base()}/v.mkv"}]}""".toByteArray()
                else -> null
            }
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.responseHeaders.add("Content-Type", if (path.endsWith(".png")) "image/png" else "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        server.start()
    }

    @After fun stop() = server.stop(0)

    /** Generated artwork: a gradient in the title's colour, soft circles, the title in white. */
    private fun picture(w: Int, h: Int, title: String, argb: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(w, h)
        val c = surface.canvas
        val dark = org.jetbrains.skia.Color.makeARGB(255, (argb shr 16 and 0xFF) / 4, (argb shr 8 and 0xFF) / 4, (argb and 0xFF) / 4)
        // A diagonal-ish gradient, drawn as bands: the title's colour at the top, a darker one at the bottom.
        val bands = 48
        for (k in 0 until bands) {
            val f = k / (bands - 1f)
            fun mix(a: Int, b: Int) = (a + (b - a) * f).toInt()
            val col = org.jetbrains.skia.Color.makeARGB(255, mix(argb shr 16 and 0xFF, dark shr 16 and 0xFF), mix(argb shr 8 and 0xFF, dark shr 8 and 0xFF), mix(argb and 0xFF, dark and 0xFF))
            c.drawRect(Rect.makeXYWH(0f, h * k / bands.toFloat(), w.toFloat(), h / bands.toFloat() + 1f), Paint().apply { color = col })
        }
        val glow = Paint().apply { color = org.jetbrains.skia.Color.makeARGB(40, 255, 255, 255) }
        c.drawCircle(w * 0.72f, h * 0.32f, minOf(w, h) * 0.34f, glow)
        c.drawCircle(w * 0.25f, h * 0.62f, minOf(w, h) * 0.18f, glow)
        val face = FontMgr.default.matchFamilyStyle(null, FontStyle.BOLD)
        val font = Font(face, if (w > h) h / 9f else w / 9f)
        c.drawString(title.uppercase(), w * 0.07f, h * 0.88f, font, Paint().apply { color = org.jetbrains.skia.Color.WHITE })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private val posters by lazy {
        PosterService(MetadataMatcher(MetadataProviderManager(emptyList(), InMemoryMetadataCache(), Clock.System, NoOpLogger)), HttpClients.create(), tmp.newFolder("posters"), NoOpLogger)
    }

    private fun service(): AddonService {
        val settings = SettingsStore(File(tmp.newFolder(), "settings.json"))
        settings.update { it.copy(addons = emptyList()) } // offline: only the local add-on
        return AddonService(HttpClients.create(), settings, NoOpLogger).also { s -> runBlocking { s.install("${base()}/addon/manifest.json") } }
    }

    private var nextId = 1L
    private fun file(title: String, year: Int? = null, season: Int? = null, episode: Int? = null, res: Int = 2160) = (nextId++).let { id ->
        MediaRecord(1, id, 1_700_000_000 + id, id.toInt(), "r$id", "$title ${year ?: ""} ${res}p.mkv", 4_000_000_000, video = true,
            title = title, titleKey = title.lowercase(), year = year, season = season, episode = episode, resolution = res)
    }

    /** Your Telegram library: two of the add-on's movies, one series, one title only you have. */
    private val library by lazy {
        LibraryBuilder.build(listOf(
            file("Midnight Harbor", 2024), file("Echo Valley", 2021, res = 1080), file("Family Album", 2019, res = 1080),
            file("Signal Lost", season = 1, episode = 1), file("Signal Lost", season = 1, episode = 2), file("Signal Lost", season = 1, episode = 3),
        ))
    }

    /** As in the app window: the theme, the page, the floating sidebar over it. */
    @Composable
    private fun Shell(dark: Boolean = true, manga: Boolean = false, selected: Int = 0, content: @Composable () -> Unit) = YourStreamTheme(dark, manga) {
        Surface(color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalNavInset provides 100.dp) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    SideBar(
                        listOf(NavDestination("Home", Icons.Default.Home), NavDestination("Search", Icons.Default.Search),
                            NavDestination("Library", Icons.Default.VideoLibrary), NavDestination("Settings", Icons.Default.Settings)),
                        selected, {}, Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                    )
                }
            }
        }
    }

    private fun DesktopComposeUiTest.save(name: String) {
        // Artwork is fetched and decoded in the background: give it a moment, then draw.
        val end = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < end) { waitForIdle(); Thread.sleep(100) }
        val bitmap = onRoot().captureToImage()
        val px = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        val b = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(bitmap.width, bitmap.height) }
        val bytes = ByteArray(px.size * 4)
        for (i in px.indices) {
            val c = px[i]
            bytes[i * 4] = (c and 0xFF).toByte(); bytes[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
            bytes[i * 4 + 2] = (c shr 16 and 0xFF).toByte(); bytes[i * 4 + 3] = (c shr 24 and 0xFF).toByte()
        }
        b.installPixels(bytes)
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name.png").writeBytes(Image.makeFromBitmap(b).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun homeShowsHeroRowsAndYourTelegramTitles() {
        val s = service()
        val watched = library.first { it.title == "Midnight Harbor" }.files.first()
        var seeAll: CatalogDescriptor? = null
        var opened: CatalogItem? = null
        for ((manga, name) in listOf(false to "home", true to "home-manga")) runDesktopComposeUiTest(1440, 900) {
            setContent {
                Shell(manga = manga) {
                    HomeScreen(
                        library, mapOf(watched.remoteFileId to 2_530_000L), s, posters, HomeCache(), indexing = false,
                        onOpenTitle = {}, onPlay = { _, _ -> }, onOpenItem = { opened = it }, onSeeAll = { seeAll = it }, onSeeLibrary = {}, onAddChats = {},
                    )
                }
            }
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText(if (manga) "MIDNIGHT HARBOR" else "Midnight Harbor").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("hero").assertExists()
            onNodeWithTag("row:Continue watching").assertExists()
            onNodeWithText("Resume · 42:10").assertExists()
            // The lower rows are composed as the page scrolls, as for a user.
            val home = onNodeWithTag("home")
            home.performScrollToNode(hasTestTag("row:Recently added · Telegram"))
            waitUntil(timeoutMillis = 30_000) { runCatching { home.performScrollToNode(hasTestTag("row:Popular · Movies")) }.isSuccess }
            waitUntil(timeoutMillis = 30_000) { runCatching { home.performScrollToNode(hasTestTag("row:Popular · Series")) }.isSuccess }
            home.performScrollToIndex(0)
            save(name)
            if (!manga) {
                // You have Midnight Harbor on Telegram: the hero says so and offers Play.
                onAllNodesWithText("Telegram").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
                onNodeWithText("Play").assertExists()
                home.performScrollToNode(hasTestTag("row:Popular · Movies"))
                onAllNodesWithText("See all ›").fetchSemanticsNodes().size.let { assertTrue(it >= 2) }
                onAllNodesWithText("See all ›")[1].performClick()
                assertEquals("Popular", seeAll?.name)
                home.performScrollToIndex(0)
                onNodeWithText("View details").performClick()
                assertEquals("Midnight Harbor", opened?.title)
            }
        }
    }

    @Test
    fun searchFindsTelegramAndAddonTitles() {
        val s = service()
        runDesktopComposeUiTest(1440, 900) {
            setContent { Shell(selected = 1) { SearchScreen(library, s, posters, onOpenTitle = {}, onOpenItem = {}) } }
            onNodeWithTag("search-field").performTextInput("harbor")
            onNodeWithTag("row:From Telegram").assertExists()
            // One row per add-on and kind; the lower rows are composed as the page scrolls.
            val page = onNodeWithTag("search")
            waitUntil(timeoutMillis = 30_000) { runCatching { page.performScrollToNode(hasTestTag("row:Sample Add-on · Movies")) }.isSuccess }
            page.performScrollToNode(hasText("Midnight Harbor"))
            page.performScrollToNode(hasTestTag("row:Sample Add-on · Series"))
            page.performScrollToNode(hasText("Harbor Lights"))
            page.performScrollToIndex(0)
            save("search")
        }
    }

    @Test
    fun addonTitlePagesWithStreamsAndEpisodes() {
        val s = service()
        val movie = runBlocking { (s.page(s.catalogs().first { it.type.name == "MOVIE" }) as dev.yourstream.core.common.AppResult.Ok).value.items }.first { it.title == "Silent Tide" }
        val show = runBlocking { (s.page(s.catalogs().first { it.type.name == "SERIES" }) as dev.yourstream.core.common.AppResult.Ok).value.items }.first { it.title == "Signal Lost" }
        runDesktopComposeUiTest(1440, 900) {
            setContent { Shell { CatalogTitleScreen(movie, s, posters, library, {}, {}) { _, _, _ -> } } }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Play")).fetchSemanticsNodes().isNotEmpty() } // once a stream is in
            onNodeWithText("Sources").assertExists()
            save("addon-movie")
        }
        runDesktopComposeUiTest(1440, 900) {
            setContent { Shell { CatalogTitleScreen(show, s, posters, library, {}, {}) { _, _, _ -> } } }
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Chapter 1")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Chapter 2").performClick()
            onNodeWithTag("title-page").performScrollToNode(hasText("Your Telegram files")) // you have Signal Lost
            waitUntil(timeoutMillis = 30_000) { onAllNodes(hasText("Showing streams")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("title-page").performScrollToIndex(0)
            save("addon-series")
        }
    }

    @Test
    fun settingsListsSectionsAndSwitches() {
        // The real app graph on an empty data folder (Telegram is not started).
        val graph = AppGraph(BuildInfo(0, "", "0.0-test"), tmp.newFolder("data"))
        try {
            runDesktopComposeUiTest(1440, 900) {
                var section by androidx.compose.runtime.mutableStateOf(SettingsSection.APPEARANCE)
                setContent { Shell(selected = 3) { SettingsScreen(graph, section, { section = it }, emptyMap()) } }
                onNodeWithText("Manga style").assertExists()
                onNodeWithText("Dark theme").assertExists()
                save("settings")
                onNodeWithTag("settings:ADDONS").performClick()
                onNodeWithTag("addon-link").assertExists()
                onNodeWithTag("settings:UPDATES").performClick()
                onNodeWithText("Check for updates").assertExists()
                onNodeWithTag("settings:ACCOUNT").performClick()
                onNodeWithText("Sign out").assertExists()
            }
        } finally {
            graph.close()
        }
    }
}
