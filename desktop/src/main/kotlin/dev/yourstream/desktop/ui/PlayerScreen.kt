package dev.yourstream.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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

private val hiddenCursor by lazy {
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "hidden"))
}

/**
 * The playing video with its controls over it. They show on mouse movement and hide after three
 * seconds of playing (with the pointer). Keys: Space/K play-pause, ←/→ 10 s (Shift: 60 s), ↑/↓
 * volume, M mute, F or double-click fullscreen, N next episode, Esc leave fullscreen / back.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerContent(request: PlayRequest, controller: VideoPlayerController, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val item = request.queue[index]
    val state by controller.state.collectAsState()
    val hasNext = index < request.queue.lastIndex
    var fit by remember { mutableStateOf(Fit.FIT) }
    var activity by remember { mutableLongStateOf(System.nanoTime()) }
    var controlsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableStateOf<Float?>(null) }
    var resumedFrom by remember { mutableLongStateOf(0L) }
    var mutedFrom by remember { mutableIntStateOf(-1) }
    val focus = remember { FocusRequester() }
    val currentState by rememberUpdatedState(state)

    // Ending playback: stop reading the file first (so VLC's read returns), then free VLC in
    // the background. Nothing here waits.
    DisposableEffect(controller) {
        onDispose {
            val s = controller.state.value
            if (s.lengthMs > 0) request.savePosition(request.queue[index], s.timeMs, s.lengthMs)
            request.release(request.queue[index])
            controller.close()
        }
    }
    DisposableEffect(item) {
        onDispose {
            if (request.queue[index] != item) {
                val s = controller.state.value
                if (s.lengthMs > 0) request.savePosition(item, s.timeMs, s.lengthMs)
                request.release(item)
            }
        }
    }

    LaunchedEffect(item) {
        val resume = request.resumeMs(item)
        resumedFrom = if (resume > 5_000) resume else 0
        controller.setVolume(request.initialVolume)
        controller.play(request.urlFor(item), resume, request.audioLanguages(), request.subtitleLanguages())
        runCatching { focus.requestFocus() }
        // Where playback is, saved every 5 s (a crash or power cut loses little).
        while (true) {
            delay(5_000)
            val s = controller.state.value
            if (s.lengthMs > 0 && s.timeMs > 0) request.savePosition(item, s.timeMs, s.lengthMs)
        }
    }
    LaunchedEffect(resumedFrom) { if (resumedFrom > 0) { delay(8_000); resumedFrom = 0 } }
    LaunchedEffect(state.finished) {
        if (!state.finished) return@LaunchedEffect
        request.savePosition(item, state.lengthMs, state.lengthMs)
        if (hasNext) index++ else onClose()
    }
    LaunchedEffect(activity, state.playing, menuOpen) {
        controlsVisible = true
        if (state.playing && !menuOpen) {
            delay(3_000)
            controlsVisible = false
        }
    }

    fun poke() { activity = System.nanoTime() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                poke()
                when (e.key) {
                    Key.Spacebar, Key.K -> controller.togglePause()
                    Key.DirectionLeft, Key.J -> controller.skip(if (e.isShiftPressed) -60_000 else -10_000)
                    Key.DirectionRight, Key.L -> controller.skip(if (e.isShiftPressed) 60_000 else 10_000)
                    Key.DirectionUp -> controller.setVolume(currentState.volume + 5).also { request.onVolume(currentState.volume + 5) }
                    Key.DirectionDown -> controller.setVolume(currentState.volume - 5).also { request.onVolume(currentState.volume - 5) }
                    Key.M -> if (mutedFrom >= 0) { controller.setVolume(mutedFrom); mutedFrom = -1 } else { mutedFrom = currentState.volume; controller.setVolume(0) }
                    Key.F -> onFullscreen(!fullscreen)
                    Key.N -> if (hasNext) index++
                    Key.Escape -> if (fullscreen) onFullscreen(false) else onClose()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .onPointerEvent(PointerEventType.Move) { poke() }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) { controller.setVolume(currentState.volume + if (dy < 0) 5 else -5); request.onVolume(currentState.volume); poke() }
            }
            .pointerHoverIcon(if (controlsVisible || !state.playing) PointerIcon.Default else hiddenCursor)
            .testTag("player"),
    ) {
        // The picture: VLC's newest frame, scaled on the GPU.
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { poke(); controller.togglePause(); runCatching { focus.requestFocus() } },
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

        if (state.error) ErrorCard(hasNext, onRetry = { controller.play(request.urlFor(item), state.timeMs, request.audioLanguages(), request.subtitleLanguages()) }, onNext = { index++ }, onBack = onClose)

        // Buffering shows even with the controls hidden.
        if (state.buffering && !state.error && !controlsVisible) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp), color = Color.White, strokeWidth = 3.dp)
        }

        AnimatedVisibility(controlsVisible && !state.error, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                TopBar(request.title, item, fullscreen, onClose, Modifier.align(Alignment.TopCenter))
                CenterControls(state, onBack10 = { poke(); controller.skip(-10_000) }, onToggle = { poke(); controller.togglePause() }, onForward10 = { poke(); controller.skip(10_000) }, modifier = Modifier.align(Alignment.Center))
                BottomBar(
                    state = state, seekPreview = seekPreview, hasNext = hasNext, fullscreen = fullscreen, fit = fit,
                    onSeek = { poke(); seekPreview = it },
                    onSeekDone = { seekPreview?.let { f -> controller.seekTo((f * state.lengthMs).toLong()) }; seekPreview = null },
                    onToggle = { poke(); controller.togglePause() },
                    onNext = { index++ },
                    onVolume = { v -> poke(); mutedFrom = -1; controller.setVolume(v); request.onVolume(v) },
                    onMute = { if (mutedFrom >= 0) { controller.setVolume(mutedFrom); mutedFrom = -1 } else { mutedFrom = state.volume; controller.setVolume(0) } },
                    onAudio = { t -> controller.selectAudio(t.id); request.onAudioPicked(t.language) },
                    onSubtitle = { t -> controller.selectSubtitle(t?.id ?: -1); request.onSubtitlePicked(t?.language) },
                    onRate = { controller.setRate(it) },
                    onFit = { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size] },
                    onFullscreen = { onFullscreen(!fullscreen) },
                    onMenu = { open -> menuOpen = open; poke() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        if (resumedFrom > 0) Surface(
            shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 84.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Resumed from ${clock(resumedFrom)}", color = Color.White, fontSize = 14.sp)
                TextButton(onClick = { controller.seekTo(0); resumedFrom = 0 }) { Text("Start over") }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, item: MediaRecord, fullscreen: Boolean, onBack: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .padding(horizontal = 12.dp, vertical = if (fullscreen) 18.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                when {
                    item.season != null && item.episode != null -> "S${item.season} · E${item.episode}"
                    item.episode != null -> "Episode ${item.episode}"
                    else -> null
                },
                resolutionLabel(item.resolution),
            ).joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun CenterControls(state: PlayerState, onBack10: () -> Unit, onToggle: () -> Unit, onForward10: () -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.Default.Replay10, "Back 10 seconds", 56, onBack10)
        Box(contentAlignment = Alignment.Center) {
            RoundButton(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Play", 80, onToggle)
            if (state.buffering) CircularProgressIndicator(Modifier.size(88.dp), color = Color.White, strokeWidth = 3.dp)
        }
        RoundButton(Icons.Default.Forward10, "Forward 10 seconds", 56, onForward10)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp)) }
}

@Composable
private fun BottomBar(
    state: PlayerState, seekPreview: Float?, hasNext: Boolean, fullscreen: Boolean, fit: Fit,
    onSeek: (Float) -> Unit, onSeekDone: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit,
    onVolume: (Int) -> Unit, onMute: () -> Unit, onAudio: (Track) -> Unit, onSubtitle: (Track?) -> Unit,
    onRate: (Float) -> Unit, onFit: () -> Unit, onFullscreen: () -> Unit, onMenu: (Boolean) -> Unit, modifier: Modifier,
) {
    val shown = seekPreview?.let { (it * state.lengthMs).toLong() } ?: state.timeMs
    Column(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = if (fullscreen) 18.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(shown), color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 56.dp))
            Slider(
                value = if (state.lengthMs > 0) (shown.toFloat() / state.lengthMs).coerceIn(0f, 1f) else 0f,
                onValueChange = onSeek, onValueChangeFinished = onSeekDone, enabled = state.lengthMs > 0,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFE50914), inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("player-seek"),
            )
            Text(if (state.lengthMs > 0) "-" + clock(state.lengthMs - shown) else "", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 64.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Play", tint = Color.White) }
            if (hasNext) IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next episode", tint = Color.White) }
            IconButton(onClick = onMute) { Icon(if (state.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute", tint = Color.White) }
            Slider(
                value = state.volume.toFloat(), onValueChange = { onVolume(it.toInt()) }, valueRange = 0f..150f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.width(110.dp),
            )
            Spacer(Modifier.weight(1f))
            val audioLabel = state.audio.firstOrNull { it.id == state.audioSelected }?.label?.substringBefore(" · ") ?: "Audio"
            PillMenu(Icons.Default.Audiotrack, audioLabel, "player-audio", onMenu) { close ->
                if (state.audio.isEmpty()) DropdownMenuItem(text = { Text("No audio tracks yet") }, onClick = close, enabled = false)
                state.audio.forEach { t -> MenuChoice(t.label, t.id == state.audioSelected) { onAudio(t); close() } }
            }
            val subLabel = state.subtitles.firstOrNull { it.id == state.subtitleSelected }?.label?.substringBefore(" · ") ?: "Off"
            PillMenu(Icons.Default.Subtitles, subLabel, "player-subtitles", onMenu) { close ->
                MenuChoice("Off", state.subtitleSelected < 0) { onSubtitle(null); close() }
                state.subtitles.forEach { t -> MenuChoice(t.label, t.id == state.subtitleSelected) { onSubtitle(t); close() } }
            }
            PillMenu(Icons.Default.Speed, if (state.rate == 1f) "1×" else "${state.rate}×", "player-speed", onMenu) { close ->
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r -> MenuChoice(if (r == 1f) "Normal" else "$r×", state.rate == r) { onRate(r); close() } }
            }
            Pill(Icons.Default.AspectRatio, fit.label, onFit)
            IconButton(onClick = onFullscreen) { Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (fullscreen) "Exit fullscreen" else "Fullscreen", tint = Color.White) }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
    }
}

@Composable
private fun PillMenu(icon: ImageVector, label: String, tag: String, onMenu: (Boolean) -> Unit, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(icon, label, { open = true; onMenu(true) }, Modifier.testTag(tag))
        DropdownMenu(open, onDismissRequest = { open = false; onMenu(false) }) { items { open = false; onMenu(false) } }
    }
}

@Composable
private fun MenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        leadingIcon = { Text(if (selected) "✓" else "", modifier = Modifier.width(16.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun ErrorCard(hasNext: Boolean, onRetry: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.8f)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("This video could not be played", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Check your connection, or try another version of the title.", color = Color.White.copy(alpha = 0.75f))
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
