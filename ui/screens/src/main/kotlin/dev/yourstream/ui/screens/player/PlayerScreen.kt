package dev.yourstream.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.yourstream.ui.core.ErrorState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.timeLabel

@Composable
fun PlayerRoute(onBack: () -> Unit, onPlayNext: (OpenTarget.Play) -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Leaving the screen visibly (home button, another app) pauses playback.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onAction(PlayerAction.Pause) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onBack)
    PlayerScreen(state, vm::onAction, onBack, onPlayNext) { PlayerSurface(vm.player, state.isPlaying) }
}

@Composable
private fun PlayerSurface(player: Player, playing: Boolean) {
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
        update = { it.keepScreenOn = playing },
        modifier = Modifier.fillMaxSize(),
    )
}

private enum class TrackMenu { AUDIO, SUBTITLES }

/**
 * Stateless player UI over a video [surface]. Remote/keyboard: D-pad moves between controls,
 * centre/Enter activates, media play/pause and left/right seek work everywhere on the screen.
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit,
    onPlayNext: (OpenTarget.Play) -> Unit,
    surface: @Composable () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf<TrackMenu?>(null) }
    val playFocus = remember { FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("player")
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause, Key.Spacebar -> { onAction(PlayerAction.TogglePlay); true }
                    Key.MediaPlay -> { if (!state.isPlaying) onAction(PlayerAction.TogglePlay); true }
                    Key.MediaPause -> { onAction(PlayerAction.Pause); true }
                    Key.MediaFastForward -> { onAction(PlayerAction.SeekBy(30_000)); true }
                    Key.MediaRewind -> { onAction(PlayerAction.SeekBy(-10_000)); true }
                    else -> false
                }
            },
    ) {
        surface()
        if (state.loading && state.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("player-loading"))
        state.error?.let { e ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))) { ErrorState(e) { onAction(PlayerAction.Retry) } }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(state.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            state.recovery?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("recovery")) }
            val duration = state.durationMs ?: 0
            if (duration > 0) {
                // Dragging moves the thumb only; one seek on release (not one per drag step).
                var scrub by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = scrub ?: state.positionMs.coerceIn(0, duration).toFloat(), valueRange = 0f..duration.toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { onAction(PlayerAction.SeekTo(it.toLong())) }; scrub = null },
                    modifier = Modifier.fillMaxWidth().focusHighlight().testTag("seek")
                        .semantics { stateDescription = "${timeLabel(state.positionMs)} / ${timeLabel(duration)}" },
                )
            }
            Text("${timeLabel(state.positionMs)} / ${timeLabel(duration)}", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAction(PlayerAction.SeekBy(-10_000)) }, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.player_back10)) }
                val playLabel = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play)
                Button(onClick = { onAction(PlayerAction.TogglePlay) }, modifier = Modifier.focusRequester(playFocus).focusHighlight().testTag("play-pause").semantics { contentDescription = playLabel }) {
                    Text(playLabel)
                }
                TextButton(onClick = { onAction(PlayerAction.SeekBy(30_000)) }, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.player_forward30)) }
                if (state.audio.size > 1) TextButton(onClick = { menu = TrackMenu.AUDIO }, modifier = Modifier.focusHighlight().testTag("audio")) { Text(stringResource(R.string.player_audio)) }
                if (state.subtitles.isNotEmpty()) TextButton(onClick = { menu = TrackMenu.SUBTITLES }, modifier = Modifier.focusHighlight().testTag("subtitles")) { Text(stringResource(R.string.player_subtitles)) }
                state.next?.let { (label, target) ->
                    Button(onClick = { onPlayNext(target) }, modifier = Modifier.focusHighlight().testTag("play-next")) { Text(stringResource(R.string.action_play_episode, label)) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    menu?.let { m ->
        val options = if (m == TrackMenu.AUDIO) state.audio.map { it.id to it.label } else listOf<Pair<String?, String>>(null to stringResource(R.string.player_subtitles_off)) + state.subtitles.map { it.id to it.label }
        val selected = if (m == TrackMenu.AUDIO) state.selectedAudio else state.selectedSubtitle
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(stringResource(if (m == TrackMenu.AUDIO) R.string.player_audio else R.string.player_subtitles)) },
            text = {
                LazyColumn {
                    items(options) { (id, label) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = id == selected, onClick = {
                                if (m == TrackMenu.AUDIO) id?.let { onAction(PlayerAction.SelectAudio(it)) } else onAction(PlayerAction.SelectSubtitle(id))
                                menu = null
                            }, modifier = Modifier.focusHighlight())
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
