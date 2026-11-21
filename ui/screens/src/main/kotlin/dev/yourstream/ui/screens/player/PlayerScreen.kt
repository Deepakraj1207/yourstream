package dev.yourstream.ui.screens.player

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
    // Leaving the screen visibly (home button, another app, screen off) pauses playback, unless
    // background playback is on (Settings → Playback): then the media notification takes over.
    val owner = LocalLifecycleOwner.current
    val background by androidx.compose.runtime.rememberUpdatedState(dev.yourstream.ui.screens.settings.LocalAppSettings.current.backgroundPlayback)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && !background) vm.onAction(PlayerAction.Pause) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    ImmersiveMode()
    // Phones and tablets open the player in landscape; the rotate button switches to portrait and back.
    val tv = LocalWindowLayout.current == WindowLayout.TV
    val settings = dev.yourstream.ui.screens.settings.LocalAppSettings.current
    var landscape by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(settings.playerLandscape) }
    var fit by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(settings.defaultFit) }
    if (!tv) Orientation(landscape)
    RestoreBrightnessOnExit()
    val prefs: dev.yourstream.ui.screens.settings.AppPreferencesViewModel = hiltViewModel()
    PlayerScreen(
        state, vm::onAction, onBack, onPlay, vm.episodeItems.collectAsLazyPagingItems(),
        onRotate = if (tv) null else ({ landscape = !landscape }),
        fit = fit, onFit = { fit = fit.next() },
        onSettings = { new -> prefs.update { new } },
    ) { PlayerSurface(vm.player, state.isPlaying, fit) }
}

/** Locks the screen to landscape (either way up) or portrait while the player is shown; back to normal after. */
@Composable
internal fun Orientation(landscape: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, landscape) {
        activity.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/** Full screen video: system bars hidden while the player is shown (a swipe brings them back briefly). */
@Composable
internal fun ImmersiveMode() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
internal fun PlayerSurface(player: Player, playing: Boolean, fit: VideoFit = VideoFit.FIT) {
    val settings = dev.yourstream.ui.screens.settings.LocalAppSettings.current
    PipEligible(playing)
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
        update = {
            it.keepScreenOn = playing
            it.resizeMode = fit.resizeMode
            // Subtitle look (player → Subtitles → Style, or Settings): size, colour, bold, outline or
            // dark box, distance from the bottom.
            it.subtitleView?.apply {
                setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleScale)
                setApplyEmbeddedStyles(!settings.subtitleBox)
                setBottomPaddingFraction(settings.subtitleOffset)
                setStyle(
                    androidx.media3.ui.CaptionStyleCompat(
                        settings.subtitleColor,
                        if (settings.subtitleBox) 0xB3000000.toInt() else android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        if (settings.subtitleBox) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        android.graphics.Color.BLACK, if (settings.subtitleBold) android.graphics.Typeface.DEFAULT_BOLD else null,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

enum class PlayerPanel { EPISODES, AUDIO, SUBTITLES, SPEED, INFO, SUBTITLE_STYLE, SOURCES }

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
    /** Switches landscape/portrait; null hides the button (TV). */
    onRotate: (() -> Unit)? = null,
    fit: VideoFit = VideoFit.FIT,
    /** Cycles [VideoFit]; null hides the button. */
    onFit: (() -> Unit)? = null,
    /** Saves a change made in the player (subtitle style); null hides that menu. */
    onSettings: ((dev.yourstream.ui.screens.settings.AppSettings) -> Unit)? = null,
    surface: @Composable () -> Unit,
) {
    val layout = LocalWindowLayout.current
    val tv = layout == WindowLayout.TV
    val settings = dev.yourstream.ui.screens.settings.LocalAppSettings.current
    val seekStepMs = settings.seekMs
    var controls by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableStateOf<PlayerPanel?>(null) }
    var flash by remember { mutableStateOf<SeekFlash?>(null) }
    var upNextDismissed by remember(state.next) { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val inPip by inPictureInPicture()
    var hint by remember { mutableStateOf<PlayerHint?>(null) }
    var speedBeforeHold by remember { mutableStateOf<Float?>(null) }
    // Locked: no controls and no gestures until unlocked (Nuvio's lock).
    var locked by rememberSaveable { mutableStateOf(false) }
    // Swipe-to-seek: where the swipe started and where it would land.
    var swipeFrom by remember { mutableStateOf<Long?>(null) }
    var swipeTo by remember { mutableStateOf<Long?>(null) }
    val fitLabel = fit.label()
    val fitChanged = remember { mutableStateOf(false) }
    LaunchedEffect(fit) { if (fitChanged.value) hint = PlayerHint(AppIcons.AspectRatio, fitLabel) else fitChanged.value = true }

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
    val hideAfter = remember(a11y) { a11y?.calculateRecommendedTimeoutMillis(3_000, containsIcons = true, containsText = true, containsControls = true) ?: 3_000 }
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
            // Locked: back does nothing (the unlock button is the way out).
            locked -> Unit
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
                    Key.MediaFastForward -> { seek(seekStepMs); true }
                    Key.MediaRewind -> { seek(-seekStepMs); true }
                    Key.MediaNext -> { state.next?.let { onPlay(it.play) }; true }
                    Key.MediaPrevious -> { state.previous?.let { onPlay(it.play) }; true }
                    else -> false
                }
            }
            .onKeyEvent { e ->
                // Only reached with nothing else focused, i.e. while the controls are hidden.
                if (controls || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { seek(-seekStepMs); true }
                    Key.DirectionRight -> { seek(seekStepMs); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onAction(PlayerAction.TogglePlay); poke(); true }
                    Key.DirectionUp, Key.DirectionDown, Key.Menu, Key.Info -> { poke(); true }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        surface()
        // Picture-in-picture: only the video; controls come back with the full screen.
        if (inPip) return@Box
        if (locked) {
            val unlockFeel = dev.yourstream.ui.core.rememberHaptics()
            LockedOverlay { unlockFeel.perform(dev.yourstream.ui.core.Haptic.CONFIRM); locked = false; poke() }
            return@Box
        }
        // Gesture layer above the video (the video view itself does not take touches).
        val context = androidx.activity.compose.LocalActivity.current ?: androidx.compose.ui.platform.LocalContext.current
        val brightness = stringResource(R.string.player_brightness)
        val volume = stringResource(R.string.player_volume)
        val holdLabel = stringResource(R.string.player_speed_hold)
        val haptics = dev.yourstream.ui.core.rememberHaptics()
        Box(
            Modifier.fillMaxSize().testTag("player-gestures").playerGestures(
                context,
                onTap = { if (controls) controls = false else poke() },
                onSeek = { forward -> haptics.perform(dev.yourstream.ui.core.Haptic.THRESHOLD); seek(if (forward) seekStepMs else -seekStepMs) },
                onHoldSpeed = { holding ->
                    if (holding) { haptics.perform(dev.yourstream.ui.core.Haptic.LONG_PRESS); speedBeforeHold = state.speed; onAction(PlayerAction.SetSpeed(settings.holdSpeed)); hint = PlayerHint(AppIcons.FastForward, holdLabel) }
                    else { onAction(PlayerAction.SetSpeed(speedBeforeHold ?: 1f)); speedBeforeHold = null; hint = null }
                },
                onHint = { hint = it },
                brightnessLabel = brightness, volumeLabel = volume,
                onSwipeSeek = { phase, fraction ->
                    val duration = state.durationMs ?: 0
                    if (duration <= 0) return@playerGestures
                    when (phase) {
                        SwipePhase.START -> { haptics.perform(dev.yourstream.ui.core.Haptic.THRESHOLD); swipeFrom = state.positionMs; swipeTo = state.positionMs }
                        // A tick for every 10 s crossed, so the jump is felt as well as seen.
                        SwipePhase.MOVE -> swipeFrom?.let { from ->
                            val to = swipeSeekTarget(from, duration, fraction)
                            if ((to - from) / 10_000 != ((swipeTo ?: from) - from) / 10_000) haptics.perform(dev.yourstream.ui.core.Haptic.STEP)
                            swipeTo = to
                        }
                        SwipePhase.END -> { swipeTo?.let { onAction(PlayerAction.SeekTo(it)) }; swipeFrom = null; swipeTo = null }
                        SwipePhase.CANCEL -> { swipeFrom = null; swipeTo = null }
                    }
                },
                onStep = { haptics.perform(dev.yourstream.ui.core.Haptic.STEP) },
            ),
        )
        // While swiping: where the video will land, and by how much.
        val from = swipeFrom
        val to = swipeTo
        if (from != null && to != null) {
            val delta = to - from
            Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Black.copy(alpha = 0.65f), contentColor = Color.White,
                modifier = Modifier.align(Alignment.Center).testTag("swipe-seek")) {
                Text("${timeLabel(to)}  (${if (delta >= 0) "+" else "−"}${timeLabel(kotlin.math.abs(delta))})", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }

        val showSpinner = state.loading && state.error == null

        AnimatedVisibility(
            controls && state.error == null,
            enter = fadeIn(androidx.compose.animation.core.tween(150)), exit = fadeOut(androidx.compose.animation.core.tween(150)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(state, tv, layout == WindowLayout.COMPACT, playFocus, onAction, onBack, onPlay, { panel = it }, ::poke, ::seek, onRotate, onFit, fitLabel,
                onLock = if (tv) null else ({ locked = true; panel = null }))
        }
        // Buffering: one ring in the middle of the screen, drawn above the controls. With the
        // controls up it circles the Play button (reported: a spinner showed behind Play).
        if (showSpinner) Buffering(Modifier.align(Alignment.Center), aroundPlay = controls && !tv)
        PlayerHintBubble(hint, onDone = { if (speedBeforeHold == null) hint = null }, Modifier.align(Alignment.Center))

        state.recovery?.let { r ->
            Surface(
                color = Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (controls) 88.dp else 24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                // The controller's wording is technical; the common case gets a plain sentence.
                val text = if (r == "waiting for network") stringResource(R.string.player_waiting_network) else r
                Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("recovery"))
            }
        }

        flash?.let { f -> SeekFlashBubble(f, Modifier.align(if (f.forward) Alignment.CenterEnd else Alignment.CenterStart)) }

        val next = state.next
        val duration = state.durationMs ?: 0
        val nearEnd = duration > 5 * 60_000 && duration - state.positionMs in 0..UP_NEXT_BEFORE_END_MS
        if (next != null && !upNextDismissed && state.error == null && (state.ended || nearEnd)) {
            UpNext(
                next, countdown = state.ended && settings.autoplayNext,
                onPlay = { onPlay(next.play) },
                onCancel = { upNextDismissed = true; poke() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = if (controls) 168.dp else 32.dp),
            )
        }

        panel?.let { p -> Panel(p, state, episodes, layout == WindowLayout.COMPACT, onAction, onPlay, onClose = { panel = null; poke() },
            settings = settings, onSettings = onSettings, openPanel = { panel = it }) }

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

/**
 * The buffering ring. [aroundPlay]: the controls are up, so it is drawn just outside the 80 dp
 * Play button (same centre); otherwise alone, on a dark disc so it reads on bright video.
 */
@Composable
private fun Buffering(modifier: Modifier, aroundPlay: Boolean = false) {
    val label = stringResource(R.string.player_buffering)
    Box(modifier.size(96.dp).testTag("player-loading").semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (!aroundPlay) Box(Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape))
        CircularProgressIndicator(
            Modifier.size(if (aroundPlay) 96.dp else 56.dp),
            color = if (aroundPlay) Color.White else MaterialTheme.colorScheme.primary, strokeWidth = if (aroundPlay) 3.dp else 4.dp,
        )
    }
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
    onRotate: (() -> Unit)? = null,
    onFit: (() -> Unit)? = null,
    fitLabel: String = "",
    onLock: (() -> Unit)? = null,
) {
    val seekStepMs = dev.yourstream.ui.screens.settings.LocalAppSettings.current.seekMs
    val seekSeconds = dev.yourstream.ui.screens.settings.LocalAppSettings.current.seekSeconds
    // One edge for everything: the title, the resume note, the times, the timeline and the
    // buttons all start and end on the same gutter. Icon buttons (48 dp, icon 24 dp) sit 12 dp
    // further out, so their icons line up with that edge too.
    val gutter = if (tv) 48.dp else if (compact) 20.dp else 28.dp
    val iconInset = 12.dp
    // Landscape phones: the controls keep clear of the camera cutout and the gesture area, with
    // the same margin on both sides so they stay centred on the screen. The shading behind them
    // still reaches the edges (reported: a bright strip beside the cutout).
    val insets = androidx.compose.foundation.layout.WindowInsets.displayCutout
        .union(androidx.compose.foundation.layout.WindowInsets.navigationBars).asPaddingValues()
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val side = maxOf(insets.calculateLeftPadding(direction), insets.calculateRightPadding(direction))
    val edge = gutter + side
    Box(Modifier.fillMaxSize().testTag("player-controls")) {
        // Top: back, title, quality, lock, rotate; the resume note under the title.
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(TopScrim).padding(top = if (tv) 27.dp else 8.dp, bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (tv) edge else edge - iconInset, end = if (tv) edge else edge - iconInset).heightIn(min = 56.dp).testTag("player-top"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!tv) {
                    IconButton(onClick = onBack, modifier = Modifier.focusHighlight().testTag("player-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.player_back), tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f).padding(start = if (tv) 0.dp else 4.dp)) {
                    Text(state.title, color = Color.White, style = if (tv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    state.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                state.quality?.badge?.let { b ->
                    // A pill like the others, as tall as the icons beside it.
                    Surface(
                        shape = CircleShape, color = Color.White.copy(alpha = 0.15f), contentColor = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 36.dp).focusHighlight(CircleShape).clickable { openPanel(PlayerPanel.INFO) }.testTag("quality-badge"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(b, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
                onLock?.let { LockButton(it) }
                onRotate?.let { rotate ->
                    IconButton(onClick = rotate, modifier = Modifier.focusHighlight().testTag("rotate")) {
                        Icon(AppIcons.ScreenRotation, stringResource(R.string.player_rotate), tint = Color.White)
                    }
                }
            }
            state.resumedFromMs?.let { r ->
                ResumeChip(r, { onAction(PlayerAction.StartOver) }, { onAction(PlayerAction.DismissResume) }, Modifier.padding(start = edge, top = 8.dp))
            }
        }

        // Centre: rewind, play, forward on one line, play exactly in the middle.
        if (!tv) {
            Row(Modifier.align(Alignment.Center).testTag("player-center"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 40.dp else 64.dp)) {
                SeekButton(AppIcons.FastRewind, stringResource(R.string.player_rewind), "−$seekSeconds", "rewind") { seek(-seekStepMs) }
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    PlayPause(state, 80.dp, playFocus, onAction)
                }
                SeekButton(AppIcons.FastForward, stringResource(R.string.player_forward), "+$seekSeconds", "forward") { seek(seekStepMs) }
            }
        }

        // Bottom: the times over the ends of the timeline, then the actions, centred.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BottomScrim).padding(top = 40.dp, bottom = if (tv) 27.dp else 10.dp),
        ) {
            val duration = state.durationMs ?: 0
            val seekLabel = stringResource(R.string.player_seek_bar)
            Row(Modifier.fillMaxWidth().padding(horizontal = edge).testTag("player-times"), verticalAlignment = Alignment.CenterVertically) {
                TimePill(timeLabel(state.positionMs), "position")
                Spacer(Modifier.weight(1f))
                if (duration > 0) TimePill(
                    if (state.speed != 1f) "${PlayerViewModel.speedLabel(state.speed)} · ${timeLabel(duration)}" else timeLabel(duration), "duration",
                )
            }
            if (duration > 0) {
                Timeline(
                    state.positionMs, duration, state.bufferedMs,
                    onScrubStart = { onAction(PlayerAction.ScrubStart) },
                    onScrubEnd = { onAction(PlayerAction.ScrubEnd(it)) },
                    timeLabel = ::timeLabel,
                    onInteraction = poke,
                    modifier = Modifier.padding(horizontal = edge).focusHighlight(MaterialTheme.shapes.small).testTag("seek")
                        .semantics { contentDescription = seekLabel },
                )
            }
            // Labelled actions (Nuvio's pills), centred. Phones wrap them onto a second centred
            // line when they do not fit (portrait); TV keeps one row the remote moves along.
            ActionsRow(tv, edge) {
                    state.previous?.let { p -> IconPill(AppIcons.SkipPrevious, stringResource(R.string.player_previous_episode), "previous-episode") { onPlay(p.play) } }
                    if (tv) {
                        BarIcon(AppIcons.FastRewind, stringResource(R.string.player_rewind), "rewind") { seek(-seekStepMs) }
                        PlayPause(state, 56.dp, playFocus, onAction)
                        BarIcon(AppIcons.FastForward, stringResource(R.string.player_forward), "forward") { seek(seekStepMs) }
                    }
                    onFit?.let { f -> ActionPill(fitLabel, "aspect", AppIcons.AspectRatio) { f(); poke() } }
                    ActionPill(PlayerViewModel.speedLabel(state.speed), "speed", AppIcons.Speed, active = state.speed != 1f) { openPanel(PlayerPanel.SPEED) }
                    if (state.subtitles.isNotEmpty()) ActionPill(stringResource(R.string.player_pill_subs), "subtitles", AppIcons.Subtitles, active = state.selectedSubtitle != null) { openPanel(PlayerPanel.SUBTITLES) }
                    if (state.audio.size > 1) ActionPill(stringResource(R.string.player_pill_audio), "audio", AppIcons.Audio) { openPanel(PlayerPanel.AUDIO) }
                    if (state.episodes != null) ActionPill(stringResource(R.string.player_episodes), "episodes", AppIcons.Episodes) { openPanel(PlayerPanel.EPISODES) }
                    if (state.sources.isNotEmpty()) ActionPill(stringResource(R.string.player_pill_sources, state.sources.size), "sources") { openPanel(PlayerPanel.SOURCES) }
                    // On phones the quality badge in the top bar opens the info panel.
                    if (!compact || state.quality?.badge == null) ActionPill(stringResource(R.string.player_quality), "info", Icons.Filled.Info) { openPanel(PlayerPanel.INFO) }
                    state.next?.let { n -> IconPill(AppIcons.SkipNext, stringResource(R.string.player_next_episode), "next-episode") { onPlay(n.play) } }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActionsRow(tv: Boolean, gutter: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    if (tv) {
        // Composed with the screen (not in a later layout pass): Play must exist when the
        // player asks for focus on it as it opens.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter).testTag("player-actions"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    } else {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = gutter).testTag("player-actions"),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

/**
 * Rewind / forward in the centre, with how far it jumps under the icon. The amount hangs below
 * the circle without taking space, so the circles stay level with the play button.
 */
@Composable
private fun SeekButton(icon: ImageVector, label: String, amount: String, tag: String, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        RoundIcon(icon, label, 60.dp, Modifier.testTag(tag), onClick)
        Text(amount, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = 22.dp).testTag("$tag-amount"))
    }
}

/** An icon-only pill (previous / next episode), the same height and colour as the labelled pills. */
@Composable
private fun IconPill(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    Surface(
        shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White,
        modifier = Modifier.size(PillTouch).focusHighlight(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { haptics.perform(dev.yourstream.ui.core.Haptic.TAP); onClick() }
            .padding((PillTouch - PillHeight) / 2).semantics { contentDescription = label }.testTag(tag),
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) } }
}

@Composable
private fun PlayPause(state: PlayerUiState, size: androidx.compose.ui.unit.Dp, focus: FocusRequester, onAction: (PlayerAction) -> Unit) {
    val label = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play)
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    FilledIconButton(
        onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.TAP); onAction(PlayerAction.TogglePlay) },
        modifier = Modifier.size(size).focusRequester(focus).focusHighlight(MaterialTheme.shapes.extraLarge).testTag("play-pause")
            .semantics { contentDescription = label },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Icon(if (state.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    IconButton(
        onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.TAP); onClick() }, modifier = modifier.size(size).focusHighlight(MaterialTheme.shapes.extraLarge),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White),
    ) { Icon(icon, label, Modifier.size(size * 0.55f)) }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    IconButton(onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.TAP); onClick() }, modifier = Modifier.focusHighlight(MaterialTheme.shapes.extraLarge).testTag(tag)) {
        Icon(icon, label, tint = Color.White)
    }
}

@Composable
internal fun ResumeChip(fromMs: Long, onStartOver: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(fromMs) { delay(10_000); onDismiss() }
    Surface(shape = MaterialTheme.shapes.medium, color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White, modifier = modifier.testTag("resume-chip")) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_resumed, timeLabel(fromMs)), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onStartOver, modifier = Modifier.focusHighlight().testTag("start-over")) {
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
    settings: dev.yourstream.ui.screens.settings.AppSettings = dev.yourstream.ui.screens.settings.AppSettings(),
    onSettings: ((dev.yourstream.ui.screens.settings.AppSettings) -> Unit)? = null,
    openPanel: (PlayerPanel) -> Unit = {},
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
            PlayerPanel.SUBTITLE_STYLE -> R.string.player_style
            PlayerPanel.SOURCES -> R.string.player_sources
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
            // Panel content sits on a 24 dp edge: the title, the options, the close icon.
            Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                // Subtitles → Style (size, colour, bold, background, position).
                if (panel == PlayerPanel.SUBTITLES && onSettings != null) {
                    TextButton(onClick = { openPanel(PlayerPanel.SUBTITLE_STYLE) }, modifier = Modifier.focusHighlight().testTag("subtitle-style-open")) {
                        Text(stringResource(R.string.player_style))
                    }
                }
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
                PlayerPanel.SUBTITLE_STYLE -> onSettings?.let { SubtitleStyle(settings, it) }
                PlayerPanel.SOURCES -> Options(state.sources.map { it.mediaId.toString() to "${it.label}\n${it.detail}" }, state.sources.firstOrNull { it.playing }?.mediaId?.toString()) { id ->
                    id?.toLongOrNull()?.takeIf { m -> state.sources.none { it.playing && it.mediaId == m } }?.let { onAction(PlayerAction.PlaySource(it)) }; onClose()
                }
                PlayerPanel.EPISODES -> state.episodes?.let { e -> EpisodeList(e, episodes, onAction, onPlay) }
            }
        }
    }
}

@Composable
private fun Options(options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    val focus = remember { FocusRequester() }
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    val initial = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    LazyColumn(state = rememberLazyListState(initial)) {
        items(options.size) { i ->
            val (id, label) = options[i]
            Row(
                Modifier.fillMaxWidth().then(if (i == initial) Modifier.focusRequester(focus) else Modifier)
                    .selectable(selected = id == selected, role = Role.RadioButton, onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.SELECT); onSelect(id) })
                    .focusHighlight().padding(start = 22.dp, end = 24.dp, top = 4.dp, bottom = 4.dp).testTag("option:$label"),
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
