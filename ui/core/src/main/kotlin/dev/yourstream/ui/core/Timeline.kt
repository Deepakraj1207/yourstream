package dev.yourstream.ui.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The playback timeline: played, buffered and remaining, with a time bubble while scrubbing.
 *
 * * Touch: tap to jump, drag to scrub; one seek on release.
 * * Remote/keyboard (when focused): left/right move the scrub position by [keyStepMs], faster
 *   while the key is held; the seek happens on centre/Enter or after a short pause, not per press.
 * * Screen readers: a progress bar with "1:02 / 22:00" and a set-progress action.
 *
 * [onScrubStart]/[onScrubEnd] map to the playback controller's scrub API (fast key-frame seeking
 * while scrubbing, one exact seek at the end).
 */
@Composable
fun Timeline(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onScrubStart: () -> Unit,
    onScrubEnd: (Long) -> Unit,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
    keyStepMs: Long = 10_000,
    /** Called whenever the user interacts (keeps the player's controls on screen). */
    onInteraction: () -> Unit = {},
) {
    val duration = durationMs.coerceAtLeast(1)
    var scrub by remember { mutableStateOf<Long?>(null) }
    var byKeys by remember { mutableStateOf(false) }
    var presses by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val start by rememberUpdatedState(onScrubStart)
    val end by rememberUpdatedState(onScrubEnd)
    val shown = (scrub ?: positionMs).coerceIn(0, duration)

    fun begin(at: Long) { if (scrub == null) start(); scrub = at.coerceIn(0, duration); onInteraction() }
    fun commit() { scrub?.let { end(it) }; scrub = null; byKeys = false; presses = 0 }

    // Key scrubbing commits after a pause, so a run of presses is one seek.
    LaunchedEffect(scrub, byKeys) { if (byKeys && scrub != null) { delay(900); commit() } }

    val primary = MaterialTheme.colorScheme.primary
    val active = focused || scrub != null
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused && byKeys) commit() }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        presses++
                        // Held key: 1× for a few steps, then 3×, then 6×.
                        val speed = when { presses > 12 -> 6; presses > 4 -> 3; else -> 1 }
                        val delta = keyStepMs * speed * if (e.key == Key.DirectionLeft) -1 else 1
                        byKeys = true
                        begin((scrub ?: positionMs) + delta)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (scrub != null) { commit(); true } else false
                    else -> false
                }
            }
            .focusable()
            .pointerInput(duration) {
                detectTapGestures { o -> begin((o.x / size.width * duration).toLong()); commit() }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> byKeys = false; begin((o.x / size.width * duration).toLong()) },
                    onDragEnd = { commit() },
                    onDragCancel = { commit() },
                    onHorizontalDrag = { change, _ -> change.consume(); begin((change.position.x / size.width * duration).toLong()) },
                )
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..duration.toFloat())
                stateDescription = "${timeLabel(shown)} / ${timeLabel(duration)}"
                setProgress { v -> start(); end(v.toLong().coerceIn(0, duration)); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        val width = maxWidth
        Canvas(Modifier.fillMaxWidth().height(if (active) 16.dp else 12.dp)) {
            val h = (if (active) 6.dp else 4.dp).toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            fun bar(fraction: Float, color: Color) =
                drawRoundRect(color, Offset(0f, y), Size(size.width * fraction.coerceIn(0f, 1f), h), r)
            bar(1f, Color.White.copy(alpha = 0.25f))
            bar(bufferedMs.toFloat() / duration, Color.White.copy(alpha = 0.45f))
            bar(shown.toFloat() / duration, primary)
            val thumb = (if (active) 8.dp else 6.dp).toPx()
            drawCircle(primary, thumb, Offset(size.width * shown / duration, size.height / 2))
            if (focused) drawCircle(Color.White, thumb, Offset(size.width * shown / duration, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        scrub?.let { s ->
            // The time under the thumb, kept inside the bar's bounds.
            Box(Modifier.align(Alignment.TopStart).layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0))
                val x = (width.toPx() * s / duration - p.width / 2).toInt().coerceIn(0, (width.toPx() - p.width).toInt().coerceAtLeast(0))
                layout(p.width, 0) { p.place(x, -p.height - 4.dp.roundToPx()) }
            }) {
                Surface(shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.8f)) {
                    Text(timeLabel(s), color = Color.White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("scrub-time"))
                }
            }
        }
    }
}
