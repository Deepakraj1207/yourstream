package dev.yourstream.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.playback.media3.WebStreams
import dev.yourstream.playback.torrent.TorrentDataSource
import dev.yourstream.playback.torrent.TorrentEngine
import dev.yourstream.playback.torrent.TorrentException
import dev.yourstream.playback.torrent.TorrentPhase
import dev.yourstream.playback.torrent.TorrentRequest
import dev.yourstream.playback.torrent.TorrentStats
import dev.yourstream.playback.torrent.TorrentStream
import dev.yourstream.playback.torrent.TorrentUri
import dev.yourstream.ui.core.AppIcons
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.StreamPlayerRoute
import dev.yourstream.ui.screens.player.ImmersiveMode
import dev.yourstream.ui.screens.player.Orientation
import dev.yourstream.ui.screens.player.PlayerHint
import dev.yourstream.ui.screens.player.PlayerHintBubble
import dev.yourstream.ui.screens.player.PlayerSurface
import dev.yourstream.ui.screens.player.RestoreBrightnessOnExit
import dev.yourstream.ui.screens.player.VideoFit
import dev.yourstream.ui.screens.player.label
import dev.yourstream.ui.screens.player.playerGestures
import dev.yourstream.ui.screens.timeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** One selectable audio or subtitle track of the stream. */
data class StreamTrack(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

data class StreamPlayerState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
    val audio: List<StreamTrack> = emptyList(),
    val subtitles: List<StreamTrack> = emptyList(),
    /** Torrent streams: peers, speed, what is ready (null for web streams). */
    val torrent: TorrentStats? = null,
    /** Started at the remembered position (ms); null when it started from the beginning. */
    val resumedFromMs: Long? = null,
)

/**
 * Plays one add-on stream: a web stream (progressive HTTP, HLS or DASH, with the request
 * headers it needs, through [WebStreams]) or a torrent (through the [TorrentEngine], read while
 * it downloads).
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class StreamPlayerViewModel @Inject constructor(
    saved: SavedStateHandle, web: WebStreams, private val torrents: TorrentEngine, prefs: dev.yourstream.ui.screens.settings.AppPreferences,
    private val active: dev.yourstream.playback.media3.ActivePlayback,
    private val progress: dev.yourstream.watch.engine.StreamProgressStore,
) : ViewModel() {
    val route = saved.toRoute<StreamPlayerRoute>()
    private val headers: Map<String, String> = runCatching {
        (Json.parseToJsonElement(route.headers) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
    val isTorrent: Boolean = route.infoHash != null

    val player: ExoPlayer = web.player(
        DefaultMediaSourceFactory(if (isTorrent) TorrentDataSource.Factory(torrents::source) else web.dataSourceFactory(headers)),
        web.loadControl(prefs.settings.value.streamBufferSeconds * 1000),
    )

    private val _state = MutableStateFlow(StreamPlayerState(torrent = if (isTorrent) TorrentStats() else null))
    val state: StateFlow<StreamPlayerState> = _state.asStateFlow()
    private var torrent: TorrentStream? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) { _state.update { it.copy(error = describe(error)) } }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) played = true else saveProgress()
            }
        })
        if (isTorrent) startTorrent() else viewModelScope.launch { start(MediaItem.fromUri(route.url)) }
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                publish(); delay(500)
                if (++ticks % 20 == 0 && player.isPlaying) saveProgress() // every 10 s while playing
            }
        }
        // The media notification and lock screen show what plays, and control this player.
        active.attach(player, route.title, route.subtitle)
    }

    /** Peers send the file list, then the player starts reading the file while it downloads. */
    private fun startTorrent() = viewModelScope.launch {
        _state.update { it.copy(error = null, torrent = TorrentStats()) }
        try {
            val request = TorrentRequest(route.infoHash!!, route.fileIdx.takeIf { it >= 0 }, route.sources.lines().filter { it.isNotBlank() }, route.fileName)
            val s = torrents.open(request) { st -> _state.update { it.copy(torrent = st) } }
            torrent = s
            launch { s.stats.collect { st -> _state.update { it.copy(torrent = st) } } }
            start(MediaItem.fromUri(TorrentUri.of(s.key, s.fileName)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.update { it.copy(error = e.message ?: "the torrent could not be started") }
        }
    }

    /** True once the stream has played: a stream that never started must not overwrite a good resume point. */
    private var played = false

    /** Starts [item] where the user stopped last time (same title and episode, any stream). */
    private suspend fun start(item: MediaItem) {
        val at = route.progressKey?.let { runCatching { progress.resumeAt(it) }.getOrNull() }
        player.setMediaItem(item, at ?: 0L)
        player.prepare()
        player.playWhenReady = true
        if (at != null) _state.update { it.copy(resumedFromMs = at) }
    }

    private fun saveProgress() {
        val key = route.progressKey ?: return
        if (!played) return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        // Outlives the screen: the last save happens as the player closes.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { progress.save(key, route.title, route.subtitle, position, duration) }
        }
    }

    /** "Start over": back to 0, and the remembered position is forgotten. */
    fun startOver() {
        player.seekTo(0)
        _state.update { it.copy(resumedFromMs = null) }
        route.progressKey?.let { key -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { progress.clear(key) } } }
    }

    fun dismissResume() = _state.update { it.copy(resumedFromMs = null) }

    /** A torrent problem in its own words; otherwise Media3's error code, readable. */
    private fun describe(error: PlaybackException): String {
        var c: Throwable? = error
        while (c != null) {
            if (c is TorrentException) return c.message ?: "torrent error"
            c = c.cause
        }
        return error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
    }

    private fun publish() {
        val t = player.currentTracks
        _state.value = _state.value.copy(
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE && _state.value.error == null,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            bufferedMs = player.bufferedPosition,
            speed = player.playbackParameters.speed,
            audio = tracks(t, C.TRACK_TYPE_AUDIO),
            subtitles = tracks(t, C.TRACK_TYPE_TEXT),
        )
    }

    private fun tracks(t: Tracks, type: Int): List<StreamTrack> = buildList {
        t.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val name = listOfNotNull(
                    f.label, f.language?.let { java.util.Locale(it).displayLanguage.takeIf(String::isNotBlank) },
                    f.channelCount.takeIf { it > 0 && type == C.TRACK_TYPE_AUDIO }?.let { "${it}ch" },
                ).distinct().joinToString(" · ").ifEmpty { "Track ${size + 1}" }
                add(StreamTrack(gi, ti, name, g.isTrackSelected(ti)))
            }
        }
    }

    fun toggle() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    fun seekBy(ms: Long) = player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)
    fun retry() {
        if (isTorrent && torrent == null) { startTorrent(); return }
        _state.update { it.copy(error = null) }
        player.prepare()
        player.play()
    }

    fun select(track: StreamTrack?, type: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        if (track == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            val group = player.currentTracks.groups[track.groupIndex].mediaTrackGroup
            params.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group, track.trackIndex))
        }
        player.trackSelectionParameters = params.build()
    }

    override fun onCleared() {
        saveProgress()
        active.detach(player) // before release: the media session lets go of it first
        player.release()
        torrent?.let(torrents::close)
    }
}

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)

@Composable
fun StreamPlayerDestination(onBack: () -> Unit, vm: StreamPlayerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = dev.yourstream.ui.screens.settings.LocalAppSettings.current
    val step = settings.seekMs
    var landscape by rememberSaveable { mutableStateOf(settings.playerLandscape) }
    var fit by rememberSaveable { mutableStateOf(settings.defaultFit) }
    ImmersiveMode()
    Orientation(landscape)
    RestoreBrightnessOnExit()
    // As in the library player: leaving pauses unless background playback is on.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val background by androidx.compose.runtime.rememberUpdatedState(settings.backgroundPlayback)
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !background) vm.player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<PlayerHint?>(null) }
    var tracks by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableStateOf<Float?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val inPip by dev.yourstream.ui.screens.player.inPictureInPicture()
    val fitLabel = fit.label()
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(fit) { if (first.value) first.value = false else hint = PlayerHint(AppIcons.AspectRatio, fitLabel) }
    LaunchedEffect(controls, poke, state.playing, tracks) { if (controls && state.playing && !tracks) { delay(3_000); controls = false } }
    BackHandler { if (tracks) tracks = false else onBack() }

    val context = LocalActivity.current ?: LocalContext.current
    val brightness = stringResource(R.string.player_brightness)
    val volume = stringResource(R.string.player_volume)
    val hold = stringResource(R.string.player_speed_hold)
    fun show() { controls = true; poke++ }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("stream-player")) {
        PlayerSurface(vm.player, state.playing, fit)
        if (inPip) return@Box
        Box(
            Modifier.fillMaxSize().playerGestures(
                context,
                onTap = { if (controls) controls = false else show() },
                onSeek = { forward -> vm.seekBy(if (forward) step else -step); hint = PlayerHint(if (forward) AppIcons.FastForward else AppIcons.FastRewind, if (forward) "+${settings.seekSeconds} s" else "−${settings.seekSeconds} s") },
                onHoldSpeed = { holding ->
                    if (holding) { speedBeforeHold = state.speed; vm.setSpeed(settings.holdSpeed); hint = PlayerHint(AppIcons.FastForward, hold) }
                    else { vm.setSpeed(speedBeforeHold ?: 1f); speedBeforeHold = null; hint = null }
                },
                onHint = { hint = it },
                brightnessLabel = brightness, volumeLabel = volume,
            ),
        )
        val t = state.torrent
        val preparing = t != null && (t.phase == TorrentPhase.STARTING || t.phase == TorrentPhase.METADATA)
        if ((state.buffering || preparing) && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)
            // Torrents: say what is happening while the first pieces arrive.
            if (t != null) TorrentStatus(t, Modifier.align(Alignment.Center).padding(top = 140.dp))
        }
        // Reopened where the user stopped (same title and episode): say so, with a way back to 0.
        state.resumedFromMs?.let { r ->
            dev.yourstream.ui.screens.player.ResumeChip(r, vm::startOver, vm::dismissResume, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 80.dp))
        }

        AnimatedVisibility(controls && state.error == null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top: back, title, aspect ratio, tracks, rotate.
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.player_back), tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(vm.route.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        vm.route.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                    }
                    TopIcon(AppIcons.AspectRatio, stringResource(R.string.player_fit)) { fit = fit.next(); show() }
                    if (state.audio.size > 1 || state.subtitles.isNotEmpty()) TopIcon(AppIcons.Subtitles, stringResource(R.string.stream_tracks)) { tracks = true }
                    TopIcon(AppIcons.ScreenRotation, stringResource(R.string.player_rotate)) { landscape = !landscape; show() }
                }
                // Centre: back 10 s, play/pause, forward 10 s.
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(AppIcons.FastRewind, stringResource(R.string.player_rewind), 56.dp) { vm.seekBy(-step); show() }
                    FilledIconButton(
                        onClick = { vm.toggle(); show() }, modifier = Modifier.size(80.dp).testTag("stream-play"),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) { Icon(if (state.playing) AppIcons.Pause else Icons.Filled.PlayArrow, stringResource(if (state.playing) R.string.player_pause else R.string.player_play), Modifier.size(40.dp)) }
                    RoundIcon(AppIcons.FastForward, stringResource(R.string.player_forward), 56.dp) { vm.seekBy(step); show() }
                }
                // Bottom: timeline, times, speed.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    val duration = state.durationMs.coerceAtLeast(1)
                    Slider(
                        value = scrub ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { scrub = it; show() },
                        onValueChangeFinished = { scrub?.let { vm.seekTo((it * duration).toLong()) }; scrub = null },
                        enabled = state.durationMs > 0,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.testTag("stream-seek"),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeLabel(scrub?.let { (it * duration).toLong() } ?: state.positionMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text(" / " + timeLabel(state.durationMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        state.torrent?.let { st ->
                            Text(torrentLine(st), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, modifier = Modifier.padding(end = 12.dp).testTag("stream-torrent-line"))
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = 0.15f), contentColor = Color.White,
                            modifier = Modifier.clickable {
                                val next = SPEEDS[(SPEEDS.indexOf(state.speed).takeIf { it >= 0 } ?: 0).plus(1) % SPEEDS.size]
                                vm.setSpeed(next); show()
                            }.testTag("stream-speed"),
                        ) { Text("${state.speed}×".replace(".0×", "×"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                    }
                }
            }
        }
        PlayerHintBubble(hint, onDone = { if (speedBeforeHold == null) hint = null }, Modifier.align(Alignment.Center))

        if (tracks) TracksPanel(state, onAudio = { vm.select(it, C.TRACK_TYPE_AUDIO) }, onSubtitle = { vm.select(it, C.TRACK_TYPE_TEXT) }, onClose = { tracks = false })

        state.error?.let { e ->
            Surface(color = Color.Black.copy(alpha = 0.85f), contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.stream_error, e), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Button(onClick = vm::retry) { Text(stringResource(dev.yourstream.ui.core.R.string.ui_retry)) }
                        androidx.compose.material3.TextButton(onClick = onBack) { Text(stringResource(R.string.player_back), color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size), colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White)) {
        Icon(icon, label, Modifier.size(size * 0.55f))
    }
}

@Composable
private fun TracksPanel(state: StreamPlayerState, onAudio: (StreamTrack) -> Unit, onSubtitle: (StreamTrack?) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(16.dp).widthIn(max = 360.dp).heightIn(max = 480.dp).clickable(enabled = false) {}) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                if (state.audio.isNotEmpty()) {
                    PanelTitle(stringResource(R.string.stream_audio))
                    state.audio.forEach { t -> Choice(t.label, t.selected) { onAudio(t) } }
                }
                if (state.subtitles.isNotEmpty()) {
                    PanelTitle(stringResource(R.string.stream_subtitles))
                    Choice(stringResource(R.string.stream_subtitles_off), state.subtitles.none { it.selected }) { onSubtitle(null) }
                    state.subtitles.forEach { t -> Choice(t.label, t.selected) { onSubtitle(t) } }
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** "Finding peers…", then "23 peers · 2.4 MB/s · 12% downloaded". */
@Composable
private fun TorrentStatus(t: TorrentStats, modifier: Modifier) {
    val line = when (t.phase) {
        TorrentPhase.STARTING -> stringResource(R.string.stream_torrent_starting)
        TorrentPhase.METADATA -> listOf(stringResource(R.string.stream_torrent_metadata), peersLabel(t.peers)).joinToString(" · ")
        else -> torrentLine(t)
    }
    Surface(shape = MaterialTheme.shapes.medium, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, modifier = modifier.testTag("stream-torrent-status")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            t.fileName?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp)) }
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun torrentLine(t: TorrentStats): String = listOfNotNull(
    peersLabel(t.peers),
    rateLabel(t.downloadBps),
    if (t.phase == TorrentPhase.COMPLETE) stringResource(R.string.stream_torrent_complete)
    else (t.progress * 100).toInt().takeIf { it > 0 }?.let { stringResource(R.string.stream_torrent_done, it) },
).joinToString(" · ")

@Composable
private fun peersLabel(n: Int) = androidx.compose.ui.res.pluralStringResource(R.plurals.stream_torrent_peers, n, n)

/** 2_400_000 → "2.4 MB/s", 900_000 → "879 KB/s". */
internal fun rateLabel(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
    else -> "${bytesPerSecond / 1024} KB/s"
}
