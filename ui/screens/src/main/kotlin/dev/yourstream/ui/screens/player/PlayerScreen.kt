package dev.yourstream.ui.screens.player

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.yourstream.ui.core.AppIcons
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.ErrorState
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.ProgressLine
import dev.yourstream.ui.core.Timeline
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.episodes.EpisodeItem
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.timeLabel
import kotlinx.coroutines.delay

@Composable
fun PlayerRoute(onBack: () -> Unit, onPlay: (OpenTarget.Play) -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Leaving the screen visibly (home button, another app) pauses playback.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onAction(PlayerAction.Pause) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    ImmersiveMode()
    PlayerScreen(state, vm::onAction, onBack, onPlay, vm.episodeItems.collectAsLazyPagingItems()) { PlayerSurface(vm.player, state.isPlaying) }
}

/** Full screen video: system bars hidden while the player is shown (a swipe brings them back briefly). */
@Composable
private fun ImmersiveMode() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun PlayerSurface(player: Player, playing: Boolean) {
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
        update = { it.keepScreenOn = playing },
        modifier = Modifier.fillMaxSize(),
    )
}

enum class PlayerPanel { EPISODES, AUDIO, SUBTITLES, SPEED, INFO }

private const val SEEK_STEP_MS = 10_000L
private const val UP_NEXT_BEFORE_END_MS = 30_000L
private const val UP_NEXT_COUNTDOWN_S = 10

/**
 * Stateless player UI over a video [surface].
 *
 * * **Touch:** tap shows/hides the controls; double-tap the left/right half seeks 10 s; large
 *   centre buttons; drag or tap the timeline.
 * * **Remote/keyboard:** with the controls hidden, left/right seek 10 s, centre plays/pauses and
 *   any other key shows the controls; with them shown, the D-pad moves between controls. Media
 *   keys (play/pause, rewind, fast-forward, next, previous) and space work at any time. BACK
 *   closes a panel, then (TV, while playing) hides the controls, then leaves.
 * * Controls hide after a few seconds of playback (longer if the accessibility timeout says so)
 *   and stay while paused, buffering, ended, or with a panel or error open.
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit,
    onPlay: (OpenTarget.Play) -> Unit,
    episodes: LazyPagingItems<EpisodeItem>? = null,
    surface: @Composable () -> Unit,
) {
    val layout = LocalWindowLayout.current
    val tv = layout == WindowLayout.TV
    var controls by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableStateOf<PlayerPanel?>(null) }
    var flash by remember { mutableStateOf<SeekFlash?>(null) }
    var upNextDismissed by remember(state.next) { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun poke() { controls = true; interactions++ }
    fun seek(deltaMs: Long) {
        onAction(PlayerAction.SeekBy(deltaMs))
        // Repeated seeks in one direction add up in the bubble ("+30 s").
        val f = flash
        val forward = deltaMs > 0
        val seconds = (kotlin.math.abs(deltaMs) / 1000).toInt()
        flash = SeekFlash(if (f != null && f.forward == forward) f.seconds + seconds else seconds, forward, (f?.id ?: 0) + 1)
    }

    val a11y = LocalAccessibilityManager.current
    val hideAfter = remember(a11y) { a11y?.calculateRecommendedTimeoutMillis(4_000, containsIcons = true, containsText = true, containsControls = true) ?: 4_000 }
    val autoHide = state.isPlaying && panel == null && state.error == null && !state.ended
    LaunchedEffect(controls, interactions, autoHide) {
        if (controls && autoHide) { delay(hideAfter); controls = false }
    }
    // Focus follows what is on screen: the play button with the controls, else the screen itself
    // (so the remote still reaches it), a panel's own focus while one is open.
    LaunchedEffect(controls, panel, state.error == null) {
        if (panel == null && state.error == null) runCatching { if (controls) playFocus.requestFocus() else rootFocus.requestFocus() }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(700); flash = null } }
    LaunchedEffect(state.ended) { if (state.ended) poke() }

    BackHandler {
        when {
            panel != null -> panel = null
            tv && controls && state.isPlaying -> controls = false
            else -> onBack()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("player")
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (controls) interactions++
                when (e.key) {
                    Key.MediaPlayPause, Key.Spacebar -> { onAction(PlayerAction.TogglePlay); poke(); true }
                    Key.MediaPlay -> { if (!state.isPlaying) onAction(PlayerAction.TogglePlay); true }
                    Key.MediaPause -> { onAction(PlayerAction.Pause); poke(); true }
                    Key.MediaFastForward -> { seek(SEEK_STEP_MS); true }
                    Key.MediaRewind -> { seek(-SEEK_STEP_MS); true }
                    Key.MediaNext -> { state.next?.let { onPlay(it.play) }; true }
                    Key.MediaPrevious -> { state.previous?.let { onPlay(it.play) }; true }
                    else -> false
                }
            }
            .onKeyEvent { e ->
                // Only reached with nothing else focused, i.e. while the controls are hidden.
                if (controls || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { seek(-SEEK_STEP_MS); true }
                    Key.DirectionRight -> { seek(SEEK_STEP_MS); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onAction(PlayerAction.TogglePlay); poke(); true }
                    Key.DirectionUp, Key.DirectionDown, Key.Menu, Key.Info -> { poke(); true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        surface()
        // Gesture layer above the video (the video view itself does not take touches).
        Box(
            Modifier.fillMaxSize().testTag("player-gestures").pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (controls && autoHide) controls = false else poke() },
                    onDoubleTap = { o -> seek(if (o.x > size.width / 2) SEEK_STEP_MS else -SEEK_STEP_MS) },
                )
            },
        )

        val showSpinner = state.loading && state.error == null
        if (showSpinner && !(controls && !tv)) Buffering(Modifier.align(Alignment.Center))

        AnimatedVisibility(controls && state.error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(state, tv, layout == WindowLayout.COMPACT, playFocus, onAction, onBack, onPlay, { panel = it }, ::poke, ::seek)
        }

        state.recovery?.let { r ->
            Surface(
                color = Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (controls) 88.dp else 24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(r, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("recovery"))
            }
        }

        flash?.let { f -> SeekFlashBubble(f, Modifier.align(if (f.forward) Alignment.CenterEnd else Alignment.CenterStart)) }

        val next = state.next
        val duration = state.durationMs ?: 0
        val nearEnd = duration > 5 * 60_000 && duration - state.positionMs in 0..UP_NEXT_BEFORE_END_MS
        if (next != null && !upNextDismissed && state.error == null && (state.ended || nearEnd)) {
            UpNext(
                next, countdown = state.ended,
                onPlay = { onPlay(next.play) },
                onCancel = { upNextDismissed = true; poke() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controls) 168.dp else 32.dp),
            )
        }

        panel?.let { p -> Panel(p, state, episodes, layout == WindowLayout.COMPACT, onAction, onPlay, onClose = { panel = null; poke() }) }

        state.error?.let { e ->
            // A Surface, so the message takes the theme's on-surface colour (not black on black).
            Surface(color = Color.Black.copy(alpha = 0.85f), contentColor = Color.White, modifier = Modifier.fillMaxSize().testTag("player-error")) {
                ErrorState(e, onRetry = { onAction(PlayerAction.Retry) }, extraActions = {
                    state.next?.let { n ->
                        OutlinedButton(onClick = { onPlay(n.play) }, modifier = Modifier.focusHighlight().testTag("error-skip")) {
                            Text(stringResource(R.string.player_skip_to, n.label))
                        }
                    }
                    if (!tv) TextButton(onClick = onBack, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.player_back)) }
                })
            }
        }
    }
}

private data class SeekFlash(val seconds: Int, val forward: Boolean, val id: Int)

@Composable
private fun SeekFlashBubble(f: SeekFlash, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Black.copy(alpha = 0.6f), modifier = modifier.padding(horizontal = 48.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!f.forward) Icon(AppIcons.FastRewind, null, tint = Color.White)
            Text(
                stringResource(if (f.forward) R.string.player_seek_flash_forward else R.string.player_seek_flash_back, f.seconds),
                color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("seek-flash"),
            )
            if (f.forward) Icon(AppIcons.FastForward, null, tint = Color.White)
        }
    }
}

@Composable
private fun Buffering(modifier: Modifier) {
    val label = stringResource(R.string.player_buffering)
    CircularProgressIndicator(
        modifier.size(56.dp).testTag("player-loading").semantics { contentDescription = label },
        color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp,
    )
}

private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))
private val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))

@Composable
private fun Controls(
    state: PlayerUiState,
    tv: Boolean,
    compact: Boolean,
    playFocus: FocusRequester,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit,
    onPlay: (OpenTarget.Play) -> Unit,
    openPanel: (PlayerPanel) -> Unit,
    poke: () -> Unit,
    seek: (Long) -> Unit,
) {
    val gutter = if (tv) 48.dp else if (compact) 12.dp else 24.dp
    Box(Modifier.fillMaxSize()) {
        // Top: back, title, quality.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(TopScrim).padding(horizontal = gutter, vertical = if (tv) 27.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!tv) {
                IconButton(onClick = onBack, modifier = Modifier.focusHighlight().testTag("player-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.player_back), tint = Color.White)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(state.title, color = Color.White, style = if (tv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                state.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            state.quality?.badge?.let { b ->
                Surface(
                    shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = 0.15f), contentColor = Color.White,
                    modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).clickable { openPanel(PlayerPanel.INFO) }.testTag("quality-badge"),
                ) { Text(b, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
            }
        }

        state.resumedFromMs?.let { r -> ResumeChip(r, onAction, Modifier.align(Alignment.TopStart).padding(start = gutter, top = if (tv) 112.dp else 80.dp)) }

        // Centre: large touch transport (phones and tablets).
        if (!tv) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 32.dp else 56.dp)) {
                RoundIcon(AppIcons.FastRewind, stringResource(R.string.player_rewind), 56.dp, Modifier.testTag("rewind")) { seek(-SEEK_STEP_MS) }
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) Buffering(Modifier) else PlayPause(state, 80.dp, playFocus, onAction)
                }
                RoundIcon(AppIcons.FastForward, stringResource(R.string.player_forward), 56.dp, Modifier.testTag("forward")) { seek(SEEK_STEP_MS) }
            }
        }

        // Bottom: timeline, times, buttons.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BottomScrim).padding(horizontal = gutter).padding(top = 32.dp, bottom = if (tv) 27.dp else 8.dp),
        ) {
            val duration = state.durationMs ?: 0
            val seekLabel = stringResource(R.string.player_seek_bar)
            if (duration > 0) {
                Timeline(
                    state.positionMs, duration, state.bufferedMs,
                    onScrubStart = { onAction(PlayerAction.ScrubStart) },
                    onScrubEnd = { onAction(PlayerAction.ScrubEnd(it)) },
                    timeLabel = ::timeLabel,
                    onInteraction = poke,
                    modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("seek")
                        .semantics { contentDescription = seekLabel },
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(timeLabel(state.positionMs), color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("position"))
                Spacer(Modifier.weight(1f))
                if (duration > 0) Text(
                    if (state.speed != 1f) "${PlayerViewModel.speedLabel(state.speed)} · ${timeLabel(duration)}" else timeLabel(duration),
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("duration"),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 4.dp)) {
                state.previous?.let { p -> BarIcon(AppIcons.SkipPrevious, stringResource(R.string.player_previous_episode), "previous-episode") { onPlay(p.play) } }
                if (tv) {
                    BarIcon(AppIcons.FastRewind, stringResource(R.string.player_rewind), "rewind") { seek(-SEEK_STEP_MS) }
                    PlayPause(state, 56.dp, playFocus, onAction)
                    BarIcon(AppIcons.FastForward, stringResource(R.string.player_forward), "forward") { seek(SEEK_STEP_MS) }
                }
                state.next?.let { n -> BarIcon(AppIcons.SkipNext, stringResource(R.string.player_next_episode), "next-episode") { onPlay(n.play) } }
                Spacer(Modifier.weight(1f))
                if (state.episodes != null) BarIcon(AppIcons.Episodes, stringResource(R.string.player_episodes), "episodes") { openPanel(PlayerPanel.EPISODES) }
                if (state.audio.size > 1) BarIcon(AppIcons.Audio, stringResource(R.string.player_audio), "audio") { openPanel(PlayerPanel.AUDIO) }
                if (state.subtitles.isNotEmpty()) BarIcon(AppIcons.Subtitles, stringResource(R.string.player_subtitles), "subtitles") { openPanel(PlayerPanel.SUBTITLES) }
                BarIcon(AppIcons.Speed, stringResource(R.string.player_speed), "speed") { openPanel(PlayerPanel.SPEED) }
                // On phones the quality badge in the top bar opens the info panel.
                if (!compact || state.quality?.badge == null) BarIcon(Icons.Filled.Info, stringResource(R.string.player_quality), "info") { openPanel(PlayerPanel.INFO) }
            }
        }
    }
}

@Composable
private fun PlayPause(state: PlayerUiState, size: androidx.compose.ui.unit.Dp, focus: FocusRequester, onAction: (PlayerAction) -> Unit) {
    val label = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play)
    FilledIconButton(
        onClick = { onAction(PlayerAction.TogglePlay) },
        modifier = Modifier.size(size).focusRequester(focus).focusHighlight(MaterialTheme.shapes.extraLarge).testTag("play-pause")
            .semantics { contentDescription = label },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Icon(if (state.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, modifier = modifier.size(size).focusHighlight(MaterialTheme.shapes.extraLarge),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White),
    ) { Icon(icon, label, Modifier.size(size * 0.55f)) }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.focusHighlight(MaterialTheme.shapes.extraLarge).testTag(tag)) {
        Icon(icon, label, tint = Color.White)
    }
}

@Composable
private fun ResumeChip(fromMs: Long, onAction: (PlayerAction) -> Unit, modifier: Modifier) {
    LaunchedEffect(fromMs) { delay(10_000); onAction(PlayerAction.DismissResume) }
    Surface(shape = MaterialTheme.shapes.medium, color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, modifier = modifier.testTag("resume-chip")) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_resumed, timeLabel(fromMs)), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onAction(PlayerAction.StartOver) }, modifier = Modifier.focusHighlight().testTag("start-over")) {
                Text(stringResource(R.string.player_start_over))
            }
        }
    }
}

@Composable
private fun UpNext(next: EpisodeLink, countdown: Boolean, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var left by remember(next, countdown) { mutableIntStateOf(UP_NEXT_COUNTDOWN_S) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(next, countdown) {
        if (!countdown) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        while (left > 0) { delay(1_000); left-- }
        onPlay()
    }
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.widthIn(max = 360.dp).testTag("up-next").semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.player_up_next), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(listOfNotNull(next.label, next.title).joinToString(" · "), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (countdown) {
                Text(stringResource(R.string.player_playing_in, left), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("up-next-countdown"))
                ProgressLine(1f - left.toFloat() / UP_NEXT_COUNTDOWN_S, Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.focusRequester(focus).focusHighlight().testTag("play-next")) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.player_play_now))
                }
                TextButton(onClick = onCancel, modifier = Modifier.focusHighlight().testTag("up-next-cancel")) { Text(stringResource(R.string.player_cancel)) }
            }
        }
    }
}

/**
 * A side sheet (bottom sheet on phones) over the video for one setting or the episode list. The
 * selected option (or the playing episode) takes focus, so the remote starts from it.
 */
@Composable
private fun BoxScope.Panel(
    panel: PlayerPanel,
    state: PlayerUiState,
    episodes: LazyPagingItems<EpisodeItem>?,
    compact: Boolean,
    onAction: (PlayerAction) -> Unit,
    onPlay: (OpenTarget.Play) -> Unit,
    onClose: () -> Unit,
) {
    // Scrim: a tap outside closes the panel.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    val title = stringResource(
        when (panel) {
            PlayerPanel.EPISODES -> R.string.player_episodes
            PlayerPanel.AUDIO -> R.string.player_audio
            PlayerPanel.SUBTITLES -> R.string.player_subtitles
            PlayerPanel.SPEED -> R.string.player_speed
            PlayerPanel.INFO -> R.string.player_quality
        },
    )
    val shape = if (compact) MaterialTheme.shapes.extraLarge.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp))
    else MaterialTheme.shapes.extraLarge.copy(topEnd = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp))
    Surface(
        shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp,
        modifier = (if (compact) Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 480.dp)
        else Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(400.dp)).testTag("panel:${panel.name.lowercase()}"),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onClose, modifier = Modifier.focusHighlight()) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
            }
            when (panel) {
                PlayerPanel.AUDIO -> Options(state.audio.map { it.id to it.label }, state.selectedAudio) { id -> id?.let { onAction(PlayerAction.SelectAudio(it)) }; onClose() }
                PlayerPanel.SUBTITLES -> Options(
                    listOf<Pair<String?, String>>(null to stringResource(R.string.player_subtitles_off)) + state.subtitles.map { it.id to it.label }, state.selectedSubtitle,
                ) { id -> onAction(PlayerAction.SelectSubtitle(id)); onClose() }
                PlayerPanel.SPEED -> {
                    val normal = stringResource(R.string.player_speed_normal)
                    Options(PlayerViewModel.SPEEDS.map { it.toString() to if (it == 1f) "${PlayerViewModel.speedLabel(it)} · $normal" else PlayerViewModel.speedLabel(it) }, state.speed.toString()) { id ->
                        id?.toFloatOrNull()?.let { onAction(PlayerAction.SetSpeed(it)) }; onClose()
                    }
                }
                PlayerPanel.INFO -> Info(state.quality)
                PlayerPanel.EPISODES -> state.episodes?.let { e -> EpisodeList(e, episodes, onAction, onPlay) }
            }
        }
    }
}

@Composable
private fun Options(options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    val focus = remember { FocusRequester() }
    val initial = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    LazyColumn(state = rememberLazyListState(initial)) {
        items(options.size) { i ->
            val (id, label) = options[i]
            Row(
                Modifier.fillMaxWidth().then(if (i == initial) Modifier.focusRequester(focus) else Modifier)
                    .selectable(selected = id == selected, role = Role.RadioButton, onClick = { onSelect(id) })
                    .focusHighlight().padding(horizontal = 16.dp, vertical = 4.dp).testTag("option:$label"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = id == selected, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun Info(q: QualityInfo?) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.player_info_unknown)
    val rows = listOf(
        R.string.player_info_resolution to listOfNotNull(q?.resolution, q?.dimensions).joinToString(" · ").ifEmpty { null },
        R.string.player_info_video to listOfNotNull(q?.videoCodec, q?.frameRate).joinToString(" · ").ifEmpty { null },
        R.string.player_info_bitrate to q?.bitrate,
        R.string.player_info_audio to q?.audio,
        R.string.player_info_size to q?.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
        R.string.player_info_file to q?.fileName,
    )
    val focus = remember { FocusRequester() }
    // Focusable so the remote can scroll it and BACK closes it.
    Column(Modifier.padding(horizontal = 24.dp).focusRequester(focus).focusable().testTag("info")) {
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) HorizontalDivider()
            Column(Modifier.padding(vertical = 10.dp).semantics(mergeDescendants = true) {}) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value ?: unknown, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun EpisodeList(panel: EpisodesPanel, items: LazyPagingItems<EpisodeItem>?, onAction: (PlayerAction) -> Unit, onPlay: (OpenTarget.Play) -> Unit) {
    if (panel.seasons.size > 1) {
        // The chips line up with the panel's own 24 dp inset, not the screen gutter.
        CompositionLocalProvider(LocalDimens provides LocalDimens.current.copy(gutter = 24.dp)) {
            ChipRow(panel.seasons.map { ChipOption(it.seasonId, it.name) }, panel.selectedSeasonId, { onAction(PlayerAction.SelectSeason(it)) }, Modifier.padding(bottom = 8.dp))
        }
    }
    if (items == null) return
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // Open at the playing episode.
    val current = (0 until items.itemCount).firstOrNull { items.peek(it)?.episodeId == panel.currentEpisodeId }
    LaunchedEffect(current != null) {
        // One episode above the playing one stays in view, for context.
        current?.let { list.scrollToItem((it - 1).coerceAtLeast(0)); runCatching { focus.requestFocus() } }
    }
    if (items.loadState.refresh is LoadState.Loading && items.itemCount == 0) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
    LazyColumn(state = list, modifier = Modifier.testTag("episode-list")) {
        items(items.itemCount, key = items.itemKey { it.episodeId }) { i ->
            val e = items[i] ?: return@items
            val playing = e.episodeId == panel.currentEpisodeId
            Row(
                Modifier.fillMaxWidth().then(if (playing) Modifier.focusRequester(focus) else Modifier)
                    .clickable(enabled = e.available && !playing) { onPlay(e.play) }
                    .focusHighlight()
                    .background(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 24.dp, vertical = 12.dp).testTag("episode:${e.episodeId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (playing) Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (e.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    e.progress?.let { ProgressLine(it, Modifier.fillMaxWidth().padding(top = 6.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant) }
                }
                if (e.watched) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
