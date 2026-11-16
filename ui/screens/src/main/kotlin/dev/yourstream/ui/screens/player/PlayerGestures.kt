package dev.yourstream.ui.screens.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.ui.AspectRatioFrameLayout
import dev.yourstream.ui.core.AppIcons
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.delay

/** How the video fills the screen (the aspect-ratio button cycles through these). */
enum class VideoFit(val resizeMode: Int) {
    /** Whole picture, black bars where the shapes differ. */
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT),
    /** Fills the screen, cropping the edges. */
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    /** Fills the screen, stretching the picture. */
    STRETCH(AspectRatioFrameLayout.RESIZE_MODE_FILL);

    fun next(): VideoFit = entries[(ordinal + 1) % entries.size]
}

@Composable
fun VideoFit.label(): String = stringResource(
    when (this) {
        VideoFit.FIT -> R.string.player_fit_fit
        VideoFit.ZOOM -> R.string.player_fit_zoom
        VideoFit.STRETCH -> R.string.player_fit_stretch
    },
)

/** A short on-screen message (brightness 60 %, volume 8/15, "Zoom", "2× speed"). */
data class PlayerHint(val icon: ImageVector?, val text: String, val level: Float? = null, val id: Long = System.nanoTime())

/** Phases of a sideways swipe-to-seek. */
enum class SwipePhase { START, MOVE, END, CANCEL }

/**
 * Touch gestures over the video (Nuvio / VLC style):
 * tap toggles the controls; double-tap the left/right half seeks; swipe up/down on the left half
 * changes the screen brightness, on the right half the volume; swipe sideways seeks
 * ([onSwipeSeek], with the fraction of the width swiped); press and hold plays faster until
 * released. Swipes that start at the screen's edges are left to the system (back / home gestures).
 */
fun Modifier.playerGestures(
    context: Context,
    onTap: () -> Unit,
    onSeek: (forward: Boolean) -> Unit,
    onHoldSpeed: (holding: Boolean) -> Unit,
    onHint: (PlayerHint) -> Unit,
    brightnessLabel: String,
    volumeLabel: String,
    onSwipeSeek: ((SwipePhase, Float) -> Unit)? = null,
    /** Each step of brightness (10 %) or volume while swiping: a haptic tick. */
    onStep: () -> Unit = {},
): Modifier = this
    .pointerInput(onSwipeSeek != null) {
        val swipe = onSwipeSeek ?: return@pointerInput
        val edge = SYSTEM_EDGE.toPx()
        var active = false
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { o -> active = o.x > edge && o.x < size.width - edge; total = 0f; if (active) swipe(SwipePhase.START, 0f) },
            onDragEnd = { if (active) swipe(SwipePhase.END, total / size.width); active = false },
            onDragCancel = { if (active) swipe(SwipePhase.CANCEL, 0f); active = false },
            onHorizontalDrag = { change, dx -> if (active) { change.consume(); total += dx; swipe(SwipePhase.MOVE, total / size.width) } },
        )
    }
    .pointerInput(Unit) {
        var holding = false
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { o -> onSeek(o.x > size.width / 2) },
            onLongPress = { holding = true; onHoldSpeed(true) },
            onPress = {
                tryAwaitRelease()
                if (holding) { holding = false; onHoldSpeed(false) }
            },
        )
    }
    .pointerInput(Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val activity = context as? Activity
        var leftSide = true
        var level = 0f
        var active = false
        val edge = SYSTEM_EDGE.toPx()
        detectVerticalDragGestures(
            onDragStart = { o ->
                active = o.y > edge && o.y < size.height - edge
                leftSide = o.x < size.width / 2
                level = if (leftSide) {
                    activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                } else {
                    val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1
                    (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max.coerceAtLeast(1)
                }
            },
            onVerticalDrag = { change, dy ->
                if (!active) return@detectVerticalDragGestures
                change.consume()
                // A full-height swipe goes from 0 to 100 %.
                level = (level - dy / size.height).coerceIn(0f, 1f)
                if (leftSide) {
                    val before = ((activity?.window?.attributes?.screenBrightness ?: level) * 10).toInt()
                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) } }
                    if ((level * 10).toInt() != before) onStep()
                    onHint(PlayerHint(AppIcons.Brightness, "$brightnessLabel ${(level * 100).toInt()}%", level))
                } else if (audio != null) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (level * max).toInt()
                    if (v != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); onStep() }
                    onHint(PlayerHint(AppIcons.Volume, "$volumeLabel $v/$max", level))
                }
            },
        )
    }

/** Shows [hint] in the middle of the screen briefly. */
@Composable
fun PlayerHintBubble(hint: PlayerHint?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(hint?.id) { if (hint != null) { delay(900); onDone() } }
    hint ?: return
    Surface(shape = MaterialTheme.shapes.large, color = Color.Black.copy(alpha = 0.65f), modifier = modifier.testTag("player-hint")) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hint.icon?.let { Icon(it, null, tint = Color.White) }
            Text(hint.text, color = Color.White, style = MaterialTheme.typography.titleMedium)
            hint.level?.let { l ->
                LinearProgressIndicator(progress = { l }, modifier = Modifier.width(140.dp).height(4.dp), color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f), drawStopIndicator = {}, gapSize = 0.dp)
            }
        }
    }
}

/** Remembers brightness so leaving the player restores the system setting. */
@Composable
fun RestoreBrightnessOnExit() {
    val activity = LocalContext.current as? Activity ?: return
    androidx.compose.runtime.DisposableEffect(activity) {
        onDispose { activity.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } } }
    }
}

/** Swipes starting this close to an edge belong to the system (back and home gestures). */
private val SYSTEM_EDGE = 24.dp
