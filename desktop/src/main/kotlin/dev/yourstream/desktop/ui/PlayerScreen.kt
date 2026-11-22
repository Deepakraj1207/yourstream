package dev.yourstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.core.common.Logger
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.playback.Vlc
import kotlinx.coroutines.delay
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** What the player needs from the app. */
class PlayRequest(
    val title: String,
    val queue: List<MediaRecord>,
    val urlFor: (MediaRecord) -> String,
    val resumeMs: (MediaRecord) -> Long,
    val savePosition: (MediaRecord, Long, Long) -> Unit,
    /** Playback of a file ended (closed, or the next episode started): stop its download. */
    val release: (MediaRecord) -> Unit = {},
)

/**
 * The video player: VLC draws the picture in the window (hardware-decoded, so 4K plays as well
 * as the PC allows) and the controls sit under it. Keyboard: Space play/pause, ←/→ 10 s,
 * F or double-click fullscreen, Esc leave fullscreen, N next episode.
 */
@Composable
fun PlayerScreen(request: PlayRequest, logger: Logger, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onClose: () -> Unit) {
    var vlcReady by remember { mutableStateOf(Vlc.available(logger)) }
    if (!vlcReady) {
        NoVlc(request, onRetry = { vlcReady = Vlc.retry(logger) }, onClose = onClose)
        return
    }
    var index by remember { mutableStateOf(0) }
    val item = request.queue[index]
    val component = remember { EmbeddedMediaPlayerComponent() }
    val player = component.mediaPlayer()
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var finished by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(100f) }
    val hasNext = index < request.queue.lastIndex
    val currentFullscreen by rememberUpdatedState(fullscreen)
    val currentNext by rememberUpdatedState({ if (index < request.queue.lastIndex) index++ })

    // Declared before the player's own cleanup, so it runs after VLC has stopped reading.
    DisposableEffect(item) { onDispose { request.release(item) } }

    fun togglePause() = player.submit { player.controls().pause() }
    fun skip(ms: Long) = player.submit { player.controls().skipTime(ms) }

    DisposableEffect(component) {
        val events = object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { playing = true; buffering = false }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { lengthMs = newLength }
            override fun finished(mediaPlayer: MediaPlayer) { finished = true }
            override fun error(mediaPlayer: MediaPlayer) { error = true }
        }
        player.events().addMediaPlayerEventListener(events)
        val surface = component.videoSurfaceComponent()
        val keys = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_SPACE, KeyEvent.VK_K -> togglePause()
                    KeyEvent.VK_LEFT, KeyEvent.VK_J -> skip(-10_000)
                    KeyEvent.VK_RIGHT, KeyEvent.VK_L -> skip(10_000)
                    KeyEvent.VK_F -> onFullscreen(!currentFullscreen)
                    KeyEvent.VK_ESCAPE -> if (currentFullscreen) onFullscreen(false) else onClose()
                    KeyEvent.VK_N -> currentNext()
                }
            }
        }
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                surface.requestFocusInWindow()
                if (e.clickCount == 2) onFullscreen(!currentFullscreen) else if (e.clickCount == 1) togglePause()
            }
        }
        surface.addKeyListener(keys)
        surface.addMouseListener(mouse)
        onDispose {
            surface.removeKeyListener(keys)
            surface.removeMouseListener(mouse)
            val t = player.status().time()
            val len = player.status().length()
            if (len > 0) request.savePosition(request.queue[index], t, len)
            player.controls().stop()
            component.release()
        }
    }

    // A new item: play it, from where it was left (unless nearly finished).
    LaunchedEffect(item) {
        finished = false
        error = false
        buffering = true
        timeMs = 0
        lengthMs = 0
        val resume = request.resumeMs(item)
        val options = buildList {
            add(":network-caching=4000")
            add(":http-reconnect")
            if (resume > 5_000) add(":start-time=${resume / 1000}")
        }
        player.media().play(request.urlFor(item), *options.toTypedArray())
        component.videoSurfaceComponent().requestFocusInWindow()
    }

    // Position, and saving it every few seconds (so a crash or power cut loses little).
    LaunchedEffect(item) {
        var ticks = 0
        while (true) {
            delay(250)
            timeMs = player.status().time().coerceAtLeast(0)
            if (lengthMs <= 0) lengthMs = player.status().length()
            if (++ticks % 20 == 0 && lengthMs > 0) request.savePosition(item, timeMs, lengthMs)
        }
    }

    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        request.savePosition(item, lengthMs, lengthMs)
        if (hasNext) index++ else onClose()
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullscreen) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(
                request.title + (item.episode?.let { " · Episode $it" } ?: ""),
                color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SwingPanel(factory = { component.apply { background = java.awt.Color.BLACK } }, modifier = Modifier.fillMaxSize())
            if (error) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This video could not be played.", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { error = false; player.media().play(request.urlFor(item), ":network-caching=4000") }) { Text("Try again") }
                        if (hasNext) OutlinedButton(onClick = { index++ }) { Text("Next episode") }
                    }
                }
            }
        }
        Controls(
            playing = playing, buffering = buffering && !error, timeMs = seeking?.let { (it * lengthMs).toLong() } ?: timeMs, lengthMs = lengthMs,
            hasNext = hasNext, fullscreen = fullscreen, volume = volume,
            onPause = ::togglePause, onSkip = ::skip,
            onSeek = { seeking = it },
            onSeekDone = { seeking?.let { f -> player.submit { player.controls().setTime((f * lengthMs).toLong()) } }; seeking = null },
            onNext = { index++ },
            onFullscreen = { onFullscreen(!fullscreen) },
            onVolume = { v -> volume = v; player.submit { player.audio().setVolume(v.toInt()) } },
            audio = { player.audio().trackDescriptions().map { it.id() to it.description() } },
            audioCurrent = { player.audio().track() },
            onAudio = { id -> player.submit { player.audio().setTrack(id) } },
            subtitles = { player.subpictures().trackDescriptions().map { it.id() to it.description() } },
            subtitleCurrent = { player.subpictures().track() },
            onSubtitle = { id -> player.submit { player.subpictures().setTrack(id) } },
        )
    }
}

@Composable
private fun Controls(
    playing: Boolean, buffering: Boolean, timeMs: Long, lengthMs: Long, hasNext: Boolean, fullscreen: Boolean, volume: Float,
    onPause: () -> Unit, onSkip: (Long) -> Unit, onSeek: (Float) -> Unit, onSeekDone: () -> Unit, onNext: () -> Unit,
    onFullscreen: () -> Unit, onVolume: (Float) -> Unit,
    audio: () -> List<Pair<Int, String>>, audioCurrent: () -> Int, onAudio: (Int) -> Unit,
    subtitles: () -> List<Pair<Int, String>>, subtitleCurrent: () -> Int, onSubtitle: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0E0C0A)).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(clock(timeMs), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
            Slider(
                value = if (lengthMs > 0) (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f,
                onValueChange = onSeek, onValueChangeFinished = onSeekDone, enabled = lengthMs > 0, modifier = Modifier.weight(1f),
            )
            Text(clock(lengthMs), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSkip(-10_000) }) { Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White) }
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = onPause) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White) }
                if (buffering) CircularProgressIndicator(Modifier.padding(2.dp), color = Color.White, strokeWidth = 2.dp)
            }
            IconButton(onClick = { onSkip(10_000) }) { Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White) }
            if (hasNext) IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next episode", tint = Color.White) }
            Box(Modifier.weight(1f))
            TrackMenu(Icons.Default.Audiotrack, "Audio", audio, audioCurrent, onAudio)
            TrackMenu(Icons.Default.Subtitles, "Subtitles", subtitles, subtitleCurrent, onSubtitle)
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.padding(start = 8.dp))
            Slider(value = volume, onValueChange = onVolume, valueRange = 0f..150f, modifier = Modifier.width(120.dp))
            IconButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (fullscreen) "Exit fullscreen" else "Fullscreen", tint = Color.White)
            }
        }
    }
}

@Composable
private fun TrackMenu(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tracks: () -> List<Pair<Int, String>>, current: () -> Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var selected by remember { mutableStateOf(-1) }
    Box {
        IconButton(onClick = { items = tracks(); selected = current(); open = true }) { Icon(icon, label, tint = Color.White) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (items.isEmpty()) DropdownMenuItem(text = { Text("No $label tracks") }, onClick = { open = false }, enabled = false)
            items.forEach { (id, name) ->
                DropdownMenuItem(text = { Text((if (id == selected) "✓ " else "    ") + name) }, onClick = { onPick(id); open = false })
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
                "YourStream plays videos with VLC, the free player that handles every format. Install VLC (64-bit) from videolan.org, then press Try again.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(Vlc.DOWNLOAD_URL)) } }) { Text("Get VLC") }
                OutlinedButton(onClick = onRetry) { Text("Try again") }
                OutlinedButton(onClick = onClose) { Text("Back") }
            }
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
