package dev.yourstream.ui.screens.addon

import android.content.Context
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

/** Plays one add-on stream (progressive HTTP, HLS or DASH) with the request headers it needs. */
@OptIn(UnstableApi::class)
@HiltViewModel
class StreamPlayerViewModel @Inject constructor(saved: SavedStateHandle, @ApplicationContext context: Context) : ViewModel() {
    val route = saved.toRoute<StreamPlayerRoute>()
    private val headers: Map<String, String> = runCatching {
        (Json.parseToJsonElement(route.headers) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android) YourStream")
                    .setDefaultRequestProperties(headers - "User-Agent")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20_000).setReadTimeoutMs(20_000),
            ),
        )
        .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
        .build()

    private val _state = MutableStateFlow(StreamPlayerState())
    val state: StateFlow<StreamPlayerState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) { _state.value = _state.value.copy(error = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')) }
        })
        player.setMediaItem(MediaItem.fromUri(route.url))
        player.prepare()
        player.playWhenReady = true
        viewModelScope.launch { while (isActive) { publish(); delay(500) } }
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
    fun retry() { _state.value = _state.value.copy(error = null); player.prepare(); player.play() }

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

    override fun onCleared() = player.release()
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
        if (state.buffering && state.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)

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
