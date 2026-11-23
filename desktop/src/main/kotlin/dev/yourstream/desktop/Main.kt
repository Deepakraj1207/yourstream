package dev.yourstream.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.yourstream.desktop.data.LibraryBuilder
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.ui.ChatsScreen
import dev.yourstream.desktop.ui.LibraryScreen
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
            onPreviewKeyEvent = { e -> dev.yourstream.desktop.ui.PlayerKeys.handler?.invoke(e) ?: false }) {
            val settings by graph.settings.settings.collectAsState()
            YourStreamTheme(settings.darkTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(graph, fullscreen = windowState.placement == WindowPlacement.Fullscreen) { on ->
                        windowState.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
                    }
                }
            }
        }
    }
}

private enum class Tab { LIBRARY, CHATS, SETTINGS }

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
    }

    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var open by remember { mutableStateOf<LibraryTitle?>(null) }
    var playing by remember { mutableStateOf<PlayRequest?>(null) }
    val current by graph.indexer.current.collectAsState()

    // The library, rebuilt (off the UI thread) when the index changes.
    val records by produceState(emptyList<dev.yourstream.desktop.data.MediaRecord>(), graph) {
        graph.index.version.debounce(400).collect { value = graph.index.allMedia() }
    }
    val titles by produceState(emptyList<LibraryTitle>(), records) { value = withContext(Dispatchers.Default) { LibraryBuilder.build(records) } }
    val mediaCount = remember(records) { records.filter { it.video }.groupingBy { it.chatId }.eachCount() }

    playing?.let { request ->
        PlayerScreen(request, graph.vlc, graph.logger, fullscreen, onFullscreen) { onFullscreen(false); playing = null }
        return
    }

    Row(Modifier.fillMaxSize()) {
        NavigationRail(Modifier.fillMaxHeight().width(96.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Text("YS", fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 16.dp))
            NavigationRailItem(tab == Tab.LIBRARY, { tab = Tab.LIBRARY; open = null }, { Icon(Icons.Default.VideoLibrary, null) }, label = { Text("Library") })
            NavigationRailItem(tab == Tab.CHATS, { tab = Tab.CHATS }, { Icon(Icons.Default.Forum, null) }, label = { Text("Chats") })
            NavigationRailItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            when (tab) {
                Tab.LIBRARY -> {
                    val title = open?.let { o -> titles.firstOrNull { it.key == o.key } ?: o }
                    // The page is open: fetch the start and end of what Play would play.
                    LaunchedEffect(title?.key) {
                        val first = title?.let { t -> if (t.isSeries) t.seasons.values.firstOrNull()?.values?.firstOrNull()?.firstOrNull() else t.files.firstOrNull() }
                        if (first != null) graph.warmer.warm(first.fileId, first.sizeBytes) else graph.warmer.stop()
                    }
                    if (title != null) TitleScreen(title, graph.posters, resumeMs = { positionOf(graph, it) }, onBack = { open = null }) { item, queue ->
                        graph.warmer.stop()
                        playing = playRequest(graph, title, queue.ifEmpty { listOf(item) })
                    } else LibraryScreen(titles, graph.posters, indexing = current != null, onOpen = { open = it }, onAddChats = { tab = Tab.CHATS })
                }
                Tab.CHATS -> ChatsScreen(graph.chats, graph.index, graph.indexer, mediaCount)
                Tab.SETTINGS -> SettingsScreen(graph)
            }
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
