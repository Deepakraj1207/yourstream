package dev.yourstream.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.core.common.Logger
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.data.resolutionLabel
import dev.yourstream.desktop.data.sizeLabel
import dev.yourstream.desktop.playback.PlayerState
import dev.yourstream.desktop.playback.Track
import dev.yourstream.desktop.playback.VideoPlayerController
import dev.yourstream.desktop.playback.Vlc
import dev.yourstream.desktop.playback.VlcEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the player needs from the app. */
class PlayRequest(
    val title: String,
    val queue: List<MediaRecord>,
    val urlFor: (MediaRecord) -> String,
    val resumeMs: (MediaRecord) -> Long,
    val savePosition: (MediaRecord, Long, Long) -> Unit,
    /** Playback of a file ended (closed, or the next episode started): stop reading it. */
    val release: (MediaRecord) -> Unit = {},
    /** Preferred audio and subtitle languages (ISO 639-1), best first; no subtitle language = off. */
    val audioLanguages: () -> List<String> = { emptyList() },
    val subtitleLanguages: () -> List<String> = { emptyList() },
    /** The user picked a track: remembered for next time (null = no language / subtitles off). */
    val onAudioPicked: (String?) -> Unit = {},
    val onSubtitlePicked: (String?) -> Unit = {},
    val initialVolume: Int = 100,
    val onVolume: (Int) -> Unit = {},
    /** Other versions of the same movie or episode (qualities, languages), for Sources. */
    val versionsOf: (MediaRecord) -> List<MediaRecord> = { listOf(it) },
    /** Where the file comes from, for the line under the title ("Telegram · My Movies"). */
    val sourceOf: (MediaRecord) -> String = { "Telegram" },
    /** Extra VLC options for an item (an add-on stream's User-Agent / Referer). */
    val vlcOptions: (MediaRecord) -> List<String> = { emptyList() },
)

/**
 * Keys reach the player through the window (Main.kt), whatever has the focus: a button clicked a
 * moment ago would otherwise take Space and press itself again instead of pausing.
 */
object PlayerKeys {
    @Volatile var handler: ((KeyEvent) -> Boolean)? = null
}

/** Every shortcut, as the help panel lists them (and docs/WINDOWS.md). */
val PLAYER_SHORTCUTS = listOf(
    "Space / K" to "Play / pause",
    "← / →  or  J / L" to "Back / forward 10 s",
    "Shift + ← / →" to "Back / forward 60 s",
    "Home / End" to "Start / end",
    "0 – 9" to "Jump to 0 % – 90 %",
    "↑ / ↓  or  wheel" to "Volume",
    "M" to "Mute",
    "A" to "Next audio track",
    "S" to "Next subtitles",
    "V" to "Subtitles on / off",
    "[  /  ]" to "Slower / faster",
    "=" to "Normal speed",
    "Z" to "Fit / Fill / Stretch",
    "N" to "Next episode",
    "I" to "Info",
    "F  or  double-click" to "Fullscreen",
    "Esc" to "Close panel / leave fullscreen / back",
    "?  or  H" to "This list",
)

/**
 * The player. VLC starts once for the app in the background ([VlcEngine]); until it is ready a
 * spinner shows, and without VLC the screen says how to get it.
 */
@Composable
fun PlayerScreen(request: PlayRequest, engine: VlcEngine, logger: Logger, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onClose: () -> Unit) {
    val status by engine.status.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { engine.start() }
    when (val s = status) {
        VlcEngine.Status.Loading -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color.White)
                Text("Starting the player…", color = Color.White.copy(alpha = 0.8f))
            }
        }
        VlcEngine.Status.Missing -> NoVlc(request, onRetry = { scope.launch { engine.start(retry = true) } }, onClose = onClose)
        is VlcEngine.Status.Ready -> {
            val controller = remember(s) { VideoPlayerController(s.factory, logger) }
            PlayerContent(request, controller, fullscreen, onFullscreen, onClose)
        }
    }
}

private enum class Fit(val label: String) { FIT("Fit"), FILL("Fill"), STRETCH("Stretch") }
private enum class Panel(val title: String) { AUDIO("Audio"), SUBTITLES("Subtitles"), SPEED("Speed"), ASPECT("Aspect ratio"), SOURCES("Sources"), INFO("Info"), KEYS("Keyboard shortcuts") }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val ACCENT = Color(0xFFE50914)

/** A blank cursor (hidden while playing); the normal one where there is no screen (tests). */
private val hiddenCursor by lazy {
    if (java.awt.GraphicsEnvironment.isHeadless()) PointerIcon.Default else runCatching {
        PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "hidden"))
    }.getOrDefault(PointerIcon.Default)
}

/**
 * The playing video with Nuvio-style controls over it: title and source at the bottom left, a
 * thin seek bar, a row of round buttons (play, aspect, speed, subtitles, audio, sources, info),
 * volume and time on the right, and panels that slide in from the right. Controls show on mouse
 * movement and hide after three seconds of playing; every option has a key ([PLAYER_SHORTCUTS]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerContent(request: PlayRequest, controller: VideoPlayerController, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf(request.queue[0]) }
    val state by controller.state.collectAsState()
    val hasNext = index < request.queue.lastIndex
    var fit by remember { mutableStateOf(Fit.FIT) }
    var activity by remember { mutableLongStateOf(System.nanoTime()) }
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var resumedFrom by remember { mutableLongStateOf(0L) }
    var mutedFrom by remember { mutableIntStateOf(-1) }
    var osd by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val s by rememberUpdatedState(state)
    val isFullscreen by rememberUpdatedState(fullscreen)
    val toggleFullscreen by rememberUpdatedState(onFullscreen)
    val close by rememberUpdatedState(onClose)

    fun poke() { activity = System.nanoTime() }
    fun note(text: String) { osd = text to System.nanoTime(); poke() }
    fun goTo(i: Int) { if (i in request.queue.indices) { index = i; current = request.queue[i] } }

    // Ending playback: stop reading the file first (so VLC's read returns), then free VLC in
    // the background. Nothing here waits.
    DisposableEffect(controller) {
        onDispose {
            val st = controller.state.value
            if (st.lengthMs > 0) request.savePosition(current, st.timeMs, st.lengthMs)
            request.release(current)
            controller.close()
        }
    }
    DisposableEffect(current) {
        val playing = current
        onDispose {
            if (current != playing) {
                val st = controller.state.value
                if (st.lengthMs > 0) request.savePosition(playing, st.timeMs, st.lengthMs)
                request.release(playing)
            }
        }
    }

    // A new item (start, next episode, another version): play it from where it was left.
    var switchAt by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(current) {
        val resume = if (switchAt >= 0) switchAt else request.resumeMs(current)
        switchAt = -1
        resumedFrom = if (resume > 5_000) resume else 0
        controller.setVolume(request.initialVolume)
        controller.play(request.urlFor(current), resume, request.audioLanguages(), request.subtitleLanguages(), extraOptions = request.vlcOptions(current))
        while (true) {
            delay(5_000)
            val st = controller.state.value
            if (st.lengthMs > 0 && st.timeMs > 0) request.savePosition(current, st.timeMs, st.lengthMs)
        }
    }
    LaunchedEffect(resumedFrom) { if (resumedFrom > 0) { delay(8_000); resumedFrom = 0 } }
    LaunchedEffect(osd) { if (osd != null) { delay(1_400); osd = null } }
    LaunchedEffect(state.finished) {
        if (!state.finished) return@LaunchedEffect
        request.savePosition(current, state.lengthMs, state.lengthMs)
        if (hasNext) goTo(index + 1) else onClose()
    }
    LaunchedEffect(activity, state.playing, panel) {
        controlsVisible = true
        if (state.playing && panel == null) {
            delay(3_000)
            controlsVisible = false
        }
    }

    fun setVolume(v: Int) {
        val vol = v.coerceIn(0, 150)
        mutedFrom = -1
        controller.setVolume(vol)
        request.onVolume(vol)
        note("Volume $vol %")
    }
    fun toggleMute() {
        if (mutedFrom >= 0) { controller.setVolume(mutedFrom); note("Volume ${mutedFrom} %"); mutedFrom = -1 }
        else { mutedFrom = s.volume; controller.setVolume(0); note("Muted") }
    }
    fun pickAudio(t: Track) { controller.selectAudio(t.id); request.onAudioPicked(t.language); note("Audio: ${t.label}") }
    fun pickSubtitle(t: Track?) {
        controller.selectSubtitle(t?.id ?: -1)
        request.onSubtitlePicked(t?.language)
        note(if (t == null) "Subtitles off" else "Subtitles: ${t.label}")
    }
    fun setRate(r: Float) { controller.setRate(r); note(if (r == 1f) "Normal speed" else "Speed ${fmtRate(r)}") }
    fun cycleFit() { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size]; note(fit.label) }
    fun seekBy(ms: Long) {
        controller.skip(ms)
        note((if (ms < 0) "−" else "+") + "${kotlin.math.abs(ms) / 1000} s")
    }

    // Every shortcut ([PLAYER_SHORTCUTS]); registered with the window while the player is open.
    // One handler for the player's whole life: it reads the current values when a key comes.
    val onKey: (KeyEvent) -> Boolean = remember { { e: KeyEvent ->
        if (e.type != KeyEventType.KeyDown) false else {
            poke()
            val shift = e.isShiftPressed
            when (e.key) {
                Key.Spacebar, Key.K -> { controller.togglePause(); note(if (s.playing) "Pause" else "Play"); true }
                Key.DirectionLeft, Key.J -> { seekBy(if (shift) -60_000 else -10_000); true }
                Key.DirectionRight, Key.L -> { seekBy(if (shift) 60_000 else 10_000); true }
                Key.MoveHome -> { controller.seekTo(0); note("Start"); true }
                Key.MoveEnd -> { if (s.lengthMs > 0) controller.seekTo(s.lengthMs - 5_000); true }
                Key.DirectionUp -> { setVolume(s.volume + 5); true }
                Key.DirectionDown -> { setVolume(s.volume - 5); true }
                Key.M -> { toggleMute(); true }
                Key.A -> {
                    if (s.audio.size > 1) pickAudio(s.audio[(s.audio.indexOfFirst { it.id == s.audioSelected } + 1).mod(s.audio.size)])
                    else note(s.audio.firstOrNull()?.label?.let { "Audio: $it (only track)" } ?: "No other audio track")
                    true
                }
                Key.S -> {
                    val list = listOf<Track?>(null) + s.subtitles
                    pickSubtitle(list[(list.indexOfFirst { (it?.id ?: -1) == s.subtitleSelected } + 1).mod(list.size)])
                    true
                }
                Key.V -> { pickSubtitle(if (s.subtitleSelected >= 0) null else s.subtitles.firstOrNull()); true }
                Key.LeftBracket -> { setRate(SPEEDS.lastOrNull { it < s.rate } ?: SPEEDS.first()); true }
                Key.RightBracket -> { setRate(SPEEDS.firstOrNull { it > s.rate } ?: SPEEDS.last()); true }
                Key.Equals -> { setRate(1f); true }
                Key.Z -> { cycleFit(); true }
                Key.N -> { if (index < request.queue.lastIndex) goTo(index + 1); true }
                Key.I -> { panel = if (panel == Panel.INFO) null else Panel.INFO; true }
                Key.H, Key.Slash -> { panel = if (panel == Panel.KEYS) null else Panel.KEYS; true }
                Key.F, Key.F11 -> { toggleFullscreen(!isFullscreen); true }
                Key.Escape -> { when { panel != null -> panel = null; isFullscreen -> toggleFullscreen(false); else -> close() }; true }
                Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
                    if (e.isCtrlPressed || s.lengthMs <= 0) false else {
                        val tenth = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(e.key)
                        controller.seekTo(s.lengthMs * tenth / 10); note("${tenth * 10} %"); true
                    }
                }
                else -> false
            }
        }
    } }
    DisposableEffect(Unit) {
        PlayerKeys.handler = onKey
        onDispose { if (PlayerKeys.handler === onKey) PlayerKeys.handler = null }
    }

    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // The player holds the keyboard focus, also after a panel (and the item clicked in it) closes.
    LaunchedEffect(panel) { if (panel == null) runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { onKey(it) }
            .onPointerEvent(PointerEventType.Move) { poke() }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) setVolume(s.volume + if (dy < 0) 5 else -5)
            }
            .pointerHoverIcon(if (controlsVisible || !state.playing) PointerIcon.Default else hiddenCursor)
            .testTag("player"),
    ) {
        // The picture: VLC's newest frame, scaled on the GPU, cropped to the video.
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { poke(); if (panel != null) panel = null else controller.togglePause() },
                    onDoubleTap = { onFullscreen(!fullscreen) },
                )
            },
        ) {
            controller.frames.frameId.longValue // redraw on every new frame
            val img = controller.frames.acquire() ?: return@Canvas
            val vw = controller.frames.visibleWidth.takeIf { it > 0 } ?: img.width
            val vh = controller.frames.visibleHeight.takeIf { it > 0 } ?: img.height
            val sw = size.width
            val sh = size.height
            val scale = when (fit) {
                Fit.FIT -> minOf(sw / vw, sh / vh)
                Fit.FILL -> maxOf(sw / vw, sh / vh)
                Fit.STRETCH -> 0f
            }
            val dw = if (fit == Fit.STRETCH) sw else vw * scale
            val dh = if (fit == Fit.STRETCH) sh else vh * scale
            drawImage(
                img, srcOffset = IntOffset.Zero, srcSize = IntSize(vw, vh),
                dstOffset = IntOffset(((sw - dw) / 2).toInt(), ((sh - dh) / 2).toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()),
                filterQuality = FilterQuality.Low,
            )
        }

        if (state.error) ErrorCard(hasNext, onRetry = { controller.play(request.urlFor(current), state.timeMs, request.audioLanguages(), request.subtitleLanguages(), extraOptions = request.vlcOptions(current)) }, onNext = { goTo(index + 1) }, onBack = onClose)

        if (state.buffering && !state.error) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp), color = Color.White, strokeWidth = 3.dp)
        }

        AnimatedVisibility(controlsVisible && !state.error, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Shade for legibility, top and bottom.
                Box(Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(260.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                Row(Modifier.align(Alignment.TopEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TopIcon(Icons.Default.Keyboard, "Keyboard shortcuts (?)") { panel = if (panel == Panel.KEYS) null else Panel.KEYS }
                    TopIcon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (fullscreen) "Exit fullscreen (F)" else "Fullscreen (F)") { onFullscreen(!fullscreen) }
                    TopIcon(Icons.Default.Close, "Close (Esc)", onClose)
                }
                BottomControls(
                    request = request, item = current, state = state, hasNext = hasNext, panel = panel,
                    onSeek = { controller.seekTo(it) },
                    onToggle = { poke(); controller.togglePause() },
                    onNext = { goTo(index + 1) },
                    onPanel = { p -> poke(); panel = if (panel == p) null else p },
                    onVolume = { setVolume(it) }, onMute = { toggleMute() },
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        // Side panels, Nuvio style.
        AnimatedVisibility(panel != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            val p = panel ?: Panel.INFO
            SidePanel(p.title, onClose = { panel = null }) {
                when (p) {
                    Panel.AUDIO -> {
                        if (state.audio.isEmpty()) Hint("No audio tracks yet")
                        state.audio.forEach { t -> Choice(t.label, t.id == state.audioSelected) { pickAudio(t) } }
                    }
                    Panel.SUBTITLES -> {
                        Choice("Off", state.subtitleSelected < 0) { pickSubtitle(null) }
                        state.subtitles.forEach { t -> Choice(t.label, t.id == state.subtitleSelected) { pickSubtitle(t) } }
                        if (state.subtitles.isEmpty()) Hint("This file has no subtitles")
                    }
                    Panel.SPEED -> SPEEDS.forEach { r -> Choice(if (r == 1f) "Normal" else fmtRate(r), state.rate == r) { setRate(r) } }
                    Panel.ASPECT -> Fit.entries.forEach { f -> Choice(f.label, fit == f) { fit = f; note(f.label) } }
                    Panel.SOURCES -> {
                        val versions = request.versionsOf(current)
                        versions.forEach { v ->
                            Choice(listOfNotNull(resolutionLabel(v.resolution), v.videoCodec?.uppercase(), v.languages.takeIf { it.isNotEmpty() }?.joinToString("/") { it.uppercase() }, sizeLabel(v.sizeBytes)).joinToString(" · "), v == current, sub = v.fileName) {
                                if (v != current) { switchAt = state.timeMs; current = v; note("Switched to ${resolutionLabel(v.resolution) ?: "another version"}") }
                            }
                        }
                    }
                    Panel.INFO -> InfoRows(current, state, request.sourceOf(current))
                    Panel.KEYS -> PLAYER_SHORTCUTS.forEach { (k, what) -> KeyRow(k, what) }
                }
            }
        }

        if (resumedFrom > 0) Surface(
            shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Resumed from ${clock(resumedFrom)}", color = Color.White, fontSize = 14.sp)
                TextButton(onClick = { controller.seekTo(0); resumedFrom = 0 }) { Text("Start over") }
            }
        }

        osd?.let { (text, _) ->
            Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).testTag("player-osd")) {
                Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun BottomControls(
    request: PlayRequest, item: MediaRecord, state: PlayerState, hasNext: Boolean, panel: Panel?,
    onSeek: (Long) -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPanel: (Panel) -> Unit,
    onVolume: (Int) -> Unit, onMute: () -> Unit, modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, bottom = 22.dp)) {
        Text(request.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val episode = when {
            item.season != null && item.episode != null -> "S${item.season} E${item.episode}"
            item.episode != null -> "Episode ${item.episode}"
            else -> null
        }
        val audio = state.audio.firstOrNull { it.id == state.audioSelected }?.label?.substringBefore(" · ")
        Text(
            listOfNotNull(episode, request.sourceOf(item), resolutionLabel(item.resolution), audio).joinToString("  •  "),
            color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        SeekBar(state.timeMs, state.lengthMs, onSeek)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Color.White).clickable(onClick = onToggle).testTag("player-play"),
                contentAlignment = Alignment.Center,
            ) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause (Space)" else "Play (Space)", tint = Color.Black, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.width(14.dp))
            BarIcon(Icons.Default.AspectRatio, "Aspect ratio (Z)", panel == Panel.ASPECT) { onPanel(Panel.ASPECT) }
            BarIcon(Icons.Default.Speed, "Speed ([ ])", panel == Panel.SPEED, badge = if (state.rate != 1f) fmtRate(state.rate) else null) { onPanel(Panel.SPEED) }
            BarIcon(Icons.Default.ClosedCaption, "Subtitles (S, V)", panel == Panel.SUBTITLES, tag = "player-subtitles") { onPanel(Panel.SUBTITLES) }
            BarIcon(Icons.Default.Audiotrack, "Audio (A)", panel == Panel.AUDIO, tag = "player-audio") { onPanel(Panel.AUDIO) }
            BarIcon(Icons.Default.Cloud, "Sources", panel == Panel.SOURCES) { onPanel(Panel.SOURCES) }
            BarIcon(Icons.Default.Info, "Info (I)", panel == Panel.INFO) { onPanel(Panel.INFO) }
            if (hasNext) BarIcon(Icons.Default.SkipNext, "Next episode (N)", false, onClick = onNext)
            Spacer(Modifier.weight(1f))
            BarIcon(if (state.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute (M)", false, onClick = onMute)
            VolumeBar(state.volume, onVolume, Modifier.width(180.dp).padding(horizontal = 8.dp))
            Text(
                "${clock(state.timeMs)} / ${clock(state.lengthMs)}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp).testTag("player-time"),
            )
        }
    }
}

/** Thin seek bar: thicker on hover, time under the pointer, click or drag to seek. */
@Composable
private fun SeekBar(timeMs: Long, lengthMs: Long, onSeek: (Long) -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp).hoverable(hover).testTag("player-seek")) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val fraction = dragFraction ?: if (lengthMs > 0) (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
        Canvas(
            Modifier.fillMaxSize()
                .onPointerEventCompat(onMove = { hoverX = it }, onExit = { hoverX = null })
                .pointerInput(lengthMs) {
                    detectTapGestures { o -> if (lengthMs > 0) onSeek((lengthMs * (o.x / widthPx).coerceIn(0f, 1f)).toLong()) }
                }
                .pointerInput(lengthMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / widthPx).coerceIn(0f, 1f) },
                        onDragEnd = { dragFraction?.let { if (lengthMs > 0) onSeek((lengthMs * it).toLong()) }; dragFraction = null },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f) }
                },
        ) {
            val h = if (hovered || dragFraction != null) 6.dp.toPx() else 4.dp.toPx()
            val y = (size.height - h) / 2
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(Color.White, Offset(0f, y), Size(size.width * fraction, h), CornerRadius(h / 2))
            if (hovered || dragFraction != null) drawCircle(Color.White, 7.dp.toPx(), Offset(size.width * fraction, size.height / 2))
        }
        val x = hoverX
        if (x != null && lengthMs > 0) {
            Surface(
                shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.8f),
                modifier = Modifier.offset(x = with(androidx.compose.ui.platform.LocalDensity.current) { (x.toDp() - 28.dp).coerceAtLeast(0.dp) }, y = (-30).dp),
            ) { Text(clock((lengthMs * (x / widthPx).coerceIn(0f, 1f)).toLong()), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.onPointerEventCompat(onMove: (Float) -> Unit, onExit: () -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Move) { e -> e.changes.firstOrNull()?.position?.x?.let(onMove) }
        .onPointerEvent(PointerEventType.Exit) { onExit() }

/** Volume: white to red gradient up to the level, 0–150 %. */
@Composable
private fun VolumeBar(volume: Int, onVolume: (Int) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.height(24.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun at(x: Float) = ((x / widthPx).coerceIn(0f, 1f) * 150).toInt()
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onVolume(at(it.x)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onVolume(at(change.position.x)) } },
        ) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2
            val f = (volume / 150f).coerceIn(0f, 1f)
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(Brush.horizontalGradient(listOf(Color.White, Color(0xFFFFB347), ACCENT), 0f, size.width), Offset(0f, y), Size(size.width * f, h), CornerRadius(h / 2))
            drawCircle(Color.White, 8.dp.toPx(), Offset(size.width * f, size.height / 2))
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, active: Boolean, badge: String? = null, tag: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 3.dp).size(46.dp).clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick).let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        if (badge != null) Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun SidePanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp), color = Color(0xF0141210),
        modifier = Modifier.fillMaxHeight().width(380.dp).testTag("player-panel"),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "Close panel (Esc)", tint = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) { item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } } }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(ACCENT))
    }
}

@Composable
private fun Hint(text: String) = Text(text, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(14.dp))

@Composable
private fun KeyRow(keys: String, what: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(6.dp), color = Color.White.copy(alpha = 0.14f)) {
            Text(keys, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Text(what, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun InfoRows(item: MediaRecord, state: PlayerState, source: String) {
    val rows = listOfNotNull(
        "File" to item.fileName,
        "Source" to source,
        "Size" to sizeLabel(item.sizeBytes),
        ("Picture" to "${state.videoWidth} × ${state.videoHeight}").takeIf { state.videoWidth > 0 },
        item.videoCodec?.let { "Video" to it.uppercase() },
        item.hdr?.let { "HDR" to it },
        state.audio.firstOrNull { it.id == state.audioSelected }?.let { "Audio" to it.label },
        ("Length" to clock(state.lengthMs)).takeIf { state.lengthMs > 0 },
        ("Speed" to fmtRate(state.rate)).takeIf { state.rate != 1f },
    )
    rows.forEach { (k, v) ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(k, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            Text(v, color = Color.White, fontSize = 14.sp)
        }
    }
}

private fun fmtRate(r: Float): String = (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()) + "×"

@Composable
private fun ErrorCard(hasNext: Boolean, onRetry: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.8f)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("This video could not be played", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Check your connection, or try another version (Sources).", color = Color.White.copy(alpha = 0.75f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("Try again") }
                    if (hasNext) OutlinedButton(onClick = onNext) { Text("Next episode") }
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
            }
        }
    }
}

/** Shown when VLC is not on this PC. */
@Composable
private fun NoVlc(request: PlayRequest, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("VLC is needed to play videos", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "This copy of YourStream has no player built in. Install VLC (64-bit) from videolan.org, then press Try again.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(Vlc.DOWNLOAD_URL)) } }) { Text("Get VLC") }
                OutlinedButton(onClick = onRetry) { Text("Try again") }
                OutlinedButton(onClick = onClose) { Text("Back") }
            }
            Spacer(Modifier.height(4.dp))
            Text("Or open the video in another player:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(request.urlFor(request.queue.first())), null)
            }) { Text("Copy the video's address") }
        }
    }
}

/** 1:02:03 or 4:05 */
fun clock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
