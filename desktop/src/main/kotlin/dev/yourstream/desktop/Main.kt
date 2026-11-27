package dev.yourstream.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.yourstream.desktop.data.LibraryBuilder
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.ui.CatalogTitleScreen
import dev.yourstream.desktop.ui.DiscoverScreen
import dev.yourstream.desktop.ui.HomeCache
import dev.yourstream.desktop.ui.HomeScreen
import dev.yourstream.desktop.ui.LibraryScreen
import dev.yourstream.desktop.ui.NavDestination
import dev.yourstream.desktop.ui.SearchScreen
import dev.yourstream.desktop.ui.SettingsSection
import dev.yourstream.desktop.ui.SideBar
import dev.yourstream.desktop.ui.PlayRequest
import dev.yourstream.desktop.ui.PlayerScreen
import dev.yourstream.desktop.ui.SettingsScreen
import dev.yourstream.desktop.ui.SignInScreen
import dev.yourstream.desktop.ui.TitleScreen
import dev.yourstream.desktop.ui.YourStreamTheme
import dev.yourstream.telegram.api.model.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The window and taskbar icon (the app icon, rendered from the Android launcher icon). */
private val appIcon by lazy {
    val bytes = AppGraph::class.java.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
    androidx.compose.ui.graphics.painter.BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

fun main() {
    val graph = AppGraph()
    Runtime.getRuntime().addShutdownHook(Thread { graph.close() })
    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        Window(onCloseRequest = { graph.close(); exitApplication() }, state = windowState, title = "YourStream", icon = appIcon,
            // Player shortcuts work whatever has the focus (see PlayerKeys).
            onPreviewKeyEvent = { e ->
                dev.yourstream.desktop.ui.PlayerKeys.handler?.invoke(e)
                    ?: (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Escape && NavKeys.back?.invoke() == true)
            }) {
            val settings by graph.settings.settings.collectAsState()
            YourStreamTheme(settings.darkTheme, settings.mangaStyle) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(graph, fullscreen = windowState.placement == WindowPlacement.Fullscreen) { on ->
                        windowState.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
                    }
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Default.Home), SEARCH("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.VideoLibrary), SETTINGS("Settings", Icons.Default.Settings),
}

/** A page opened over a tab: a Telegram title, an add-on title, or a whole catalog ("See all"). */
private sealed interface Page {
    data class Title(val title: LibraryTitle) : Page
    data class Item(val item: dev.yourstream.catalog.api.CatalogItem) : Page
    data class Catalog(val catalog: dev.yourstream.catalog.api.CatalogDescriptor) : Page
}

/** Esc goes back a page when no player is open (see Main's window key handler). */
internal object NavKeys {
    @Volatile var back: (() -> Boolean)? = null
}

@OptIn(FlowPreview::class)
@Composable
fun App(graph: AppGraph, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    if (!graph.build.hasTelegramCredentials) {
        Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
            Text(
                "This build has no Telegram API credentials. Official releases include them; to build it yourself, see docs/TELEGRAM_SETUP.md.",
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LaunchedEffect(Unit) { graph.telegram.start() }
    val auth by graph.auth.state.collectAsState()
    if (auth != AuthState.Ready) {
        SignInScreen(graph.auth)
        return
    }
    LaunchedEffect(Unit) {
        graph.indexer.start()
        // VLC loads its plugins once, now, so pressing Play later starts at once.
        graph.vlc.start()
        // A newer version? Asked quietly once at start, like the Android app.
        kotlinx.coroutines.delay(5_000)
        graph.updates.check(quiet = true)
    }

    var tab by remember { mutableStateOf(Tab.HOME) }
    var pages by remember { mutableStateOf(listOf<Page>()) }
    var settingsSection by remember { mutableStateOf(SettingsSection.APPEARANCE) }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }
    val homeCache = remember { HomeCache() }
    val current by graph.indexer.current.collectAsState()
    val settings by graph.settings.settings.collectAsState()

    // The library, rebuilt (off the UI thread) when the index changes.
    val records by produceState(emptyList<dev.yourstream.desktop.data.MediaRecord>(), graph) {
        graph.index.version.debounce(400).collect { value = graph.index.allMedia() }
    }
    val titles by produceState(emptyList<LibraryTitle>(), records) { value = withContext(Dispatchers.Default) { LibraryBuilder.build(records) } }
    val mediaCount = remember(records) { records.filter { it.video }.groupingBy { it.chatId }.eachCount() }

    fun open(page: Page) { pages = pages + page }
    fun back() { pages = pages.dropLast(1) }
    fun play(t: LibraryTitle, first: dev.yourstream.desktop.data.MediaRecord, queue: List<dev.yourstream.desktop.data.MediaRecord> = listOf(first)) {
        graph.warmer.stop()
        playing = playRequest(graph, t, queue.ifEmpty { listOf(first) })
    }
    fun chooseChats() { pages = emptyList(); tab = Tab.SETTINGS; settingsSection = SettingsSection.CHATS }

    DisposableEffect(Unit) {
        NavKeys.back = { if (playing == null && pages.isNotEmpty()) { back(); true } else false }
        onDispose { NavKeys.back = null }
    }

    playing?.let { request ->
        PlayerScreen(request, graph.vlc, graph.logger, fullscreen, onFullscreen) { onFullscreen(false); playing = null }
        return
    }

    val page = pages.lastOrNull()
    // A Telegram title's page is open: fetch the start and end of what Play would play.
    val openTitle = (page as? Page.Title)?.title?.let { o -> titles.firstOrNull { it.key == o.key } ?: o }
    LaunchedEffect(openTitle?.key) {
        val first = openTitle?.let { t -> if (t.isSeries) t.seasons.values.firstOrNull()?.values?.firstOrNull()?.firstOrNull() else t.files.firstOrNull() }
        if (first != null) graph.warmer.warm(first.fileId, first.sizeBytes) else graph.warmer.stop()
    }

    CompositionLocalProvider(dev.yourstream.desktop.ui.LocalNavInset provides 100.dp) {
        Box(Modifier.fillMaxSize()) {
            when (page) {
                is Page.Title -> TitleScreen(openTitle!!, graph.posters, resumeMs = { positionOf(graph, it) }, onBack = ::back) { item, queue -> play(openTitle, item, queue) }
                is Page.Item -> CatalogTitleScreen(
                    page.item, graph.addons, graph.posters, titles, onBack = ::back,
                    onPlayLibrary = { t -> open(Page.Title(t)) },
                    onPlayStream = { meta, video, stream -> graph.warmer.stop(); playing = streamRequest(graph, meta, video, stream) },
                )
                is Page.Catalog -> DiscoverScreen(graph.addons, graph.posters, page.catalog, titles, onBack = ::back) { open(Page.Item(it)) }
                null -> when (tab) {
                    Tab.HOME -> HomeScreen(
                        titles, settings.positions, graph.addons, graph.posters, homeCache, indexing = current != null,
                        onOpenTitle = { open(Page.Title(it)) }, onPlay = { t, f -> play(t, f) }, onOpenItem = { open(Page.Item(it)) },
                        onSeeAll = { open(Page.Catalog(it)) }, onSeeLibrary = { tab = Tab.LIBRARY }, onAddChats = ::chooseChats,
                    )
                    Tab.SEARCH -> SearchScreen(titles, graph.addons, graph.posters, onOpenTitle = { open(Page.Title(it)) }, onOpenItem = { open(Page.Item(it)) })
                    Tab.LIBRARY -> LibraryScreen(titles, graph.posters, indexing = current != null, onOpen = { open(Page.Title(it)) }, onAddChats = ::chooseChats)
                    Tab.SETTINGS -> SettingsScreen(graph, settingsSection, { settingsSection = it }, mediaCount)
                }
            }
            SideBar(
                Tab.entries.map { NavDestination(it.label, it.icon) }, Tab.entries.indexOf(tab),
                { i -> tab = Tab.entries[i]; pages = emptyList() },
                Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
            )
            Box(Modifier.align(Alignment.TopCenter).padding(start = 100.dp, end = 40.dp, top = 12.dp)) { dev.yourstream.desktop.ui.UpdateBanner(graph.updates) }
        }
    }
}

/** Saved position of a file; nothing when it was watched to (nearly) the end. */
private fun positionOf(graph: AppGraph, r: dev.yourstream.desktop.data.MediaRecord): Long =
    graph.settings.settings.value.positions[r.remoteFileId] ?: 0L

private fun playRequest(graph: AppGraph, title: LibraryTitle, queue: List<dev.yourstream.desktop.data.MediaRecord>) = PlayRequest(
    title = title.title,
    versionsOf = { r ->
        if (title.isSeries) title.files.filter { it.season == r.season && it.episode == r.episode && it.absoluteEpisode == r.absoluteEpisode }.ifEmpty { listOf(r) }
        else title.files
    },
    sourceOf = { r -> graph.index.chatRows.value.firstOrNull { it.chatId == r.chatId }?.title?.let { "Telegram · $it" } ?: "Telegram" },
    queue = queue,
    urlFor = { graph.streamServer.urlFor(it.fileId, it.fileName) },
    resumeMs = { positionOf(graph, it) },
    savePosition = { r, t, len ->
        // Finished (last 5 %) = start from the beginning next time.
        val keep = if (len > 0 && t > len * 0.95) 0L else t
        graph.settings.update { s -> s.copy(positions = (s.positions - r.remoteFileId).let { if (keep > 0) it + (r.remoteFileId to keep) else it }) }
    },
    release = { r ->
        // End the server's reads first (VLC may be waiting in one), then the Telegram download.
        graph.streamServer.abort(r.fileId)
        graph.scope.launch { graph.streamer.release(r.fileId) }
    },
    audioLanguages = { listOfNotNull(graph.settings.settings.value.audioLanguage) },
    subtitleLanguages = { listOfNotNull(graph.settings.settings.value.subtitleLanguage) },
    onAudioPicked = { lang -> if (lang != null) graph.settings.update { it.copy(audioLanguage = lang) } },
    onSubtitlePicked = { lang -> graph.settings.update { it.copy(subtitleLanguage = lang) } },
    initialVolume = graph.settings.settings.value.volume,
    onVolume = { v -> graph.settings.update { it.copy(volume = v.coerceIn(0, 150)) } },
)

/** An add-on stream in the player: played from its own address; where it stopped is remembered by title/episode. */
private fun streamRequest(graph: AppGraph, meta: dev.yourstream.catalog.api.CatalogMetadata, video: dev.yourstream.catalog.api.CatalogVideo?, stream: dev.yourstream.catalog.api.AddonStream): PlayRequest {
    val record = dev.yourstream.desktop.ui.streamRecord(meta, video, stream)
    return PlayRequest(
        title = meta.title,
        queue = listOf(record),
        urlFor = { stream.url!! },
        resumeMs = { positionOf(graph, it) },
        savePosition = { r, t, len ->
            val keep = if (len > 0 && t > len * 0.95) 0L else t
            graph.settings.update { s -> s.copy(positions = (s.positions - r.remoteFileId).let { if (keep > 0) it + (r.remoteFileId to keep) else it }) }
        },
        audioLanguages = { listOfNotNull(graph.settings.settings.value.audioLanguage) },
        subtitleLanguages = { listOfNotNull(graph.settings.settings.value.subtitleLanguage) },
        onAudioPicked = { lang -> if (lang != null) graph.settings.update { it.copy(audioLanguage = lang) } },
        onSubtitlePicked = { lang -> graph.settings.update { it.copy(subtitleLanguage = lang) } },
        initialVolume = graph.settings.settings.value.volume,
        onVolume = { v -> graph.settings.update { it.copy(volume = v.coerceIn(0, 150)) } },
        sourceOf = { stream.addon.ifBlank { "Add-on" } },
        vlcOptions = {
            buildList {
                stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { add(":http-user-agent=${it.value}") }
                stream.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { add(":http-referrer=${it.value}") }
            }
        },
    )
}
