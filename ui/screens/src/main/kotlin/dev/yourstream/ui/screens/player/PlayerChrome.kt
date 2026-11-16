package dev.yourstream.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.settings.AppSettings
import kotlinx.coroutines.delay

/**
 * Pieces shared by both players (Telegram files and add-on streams), laid out like Nuvio's
 * player: labelled pills for the actions ("Fit", "1×", "Subs", "Audio", "Episodes", "Sources"),
 * time pills, the lock, and the subtitle style menu.
 */

private val PillColor = Color.Black.copy(alpha = 0.45f)

/** The drawn height of every pill on the player's bar; each answers touches over [PillTouch]. */
internal val PillHeight = 44.dp
internal val PillTouch = 48.dp

/** A labelled action on the player's bottom bar: drawn [PillHeight] tall, touched over 48 dp. */
@Composable
fun ActionPill(label: String, tag: String, icon: ImageVector? = null, active: Boolean = false, onClick: () -> Unit) {
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    Surface(
        shape = CircleShape,
        color = if (active) Color.White else PillColor,
        contentColor = if (active) Color.Black else Color.White,
        // The touch area (48 dp) includes the 2 dp around the drawn pill.
        modifier = Modifier.heightIn(min = PillTouch).focusHighlight(CircleShape)
            .clickable(role = Role.Button) { haptics.perform(dev.yourstream.ui.core.Haptic.TAP); onClick() }.padding(vertical = (PillTouch - PillHeight) / 2).testTag(tag),
    ) {
        Row(Modifier.heightIn(min = PillHeight).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The position or the length, in a pill (Nuvio's time pills). */
@Composable
fun TimePill(text: String, tag: String, modifier: Modifier = Modifier) {
    // One node for screen readers, read as the time it shows.
    Surface(shape = CircleShape, color = PillColor, contentColor = Color.White, modifier = modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

/** The lock button in the top bar: locking hides every control and ignores touches on the video. */
@Composable
fun LockButton(onLock: () -> Unit) {
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    IconButton(onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.THRESHOLD); onLock() }, modifier = Modifier.focusHighlight().testTag("player-lock")) {
        Icon(Icons.Filled.Lock, stringResource(R.string.player_lock), tint = Color.White)
    }
}

/**
 * Locked: nothing on screen and no gestures, so a pocket or a child cannot pause, seek or leave.
 * A tap shows the unlock button for a few seconds ("Tap to unlock"); tapping it unlocks.
 */
@Composable
fun LockedOverlay(onUnlock: () -> Unit) {
    var shown by remember { mutableStateOf(true) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(shown, taps) { if (shown) { delay(3_000); shown = false } }
    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { shown = true; taps++ }
            .testTag("player-locked"),
        contentAlignment = Alignment.Center,
    ) {
        if (shown) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = CircleShape, color = Color.White, contentColor = Color.Black,
                    modifier = Modifier.size(72.dp).focusHighlight(CircleShape).clickable(role = Role.Button, onClick = onUnlock).testTag("player-unlock"),
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Lock, stringResource(R.string.player_unlock), Modifier.size(32.dp)) }
                }
                Text(stringResource(R.string.player_tap_to_unlock), color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp).background(PillColor, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
    }
}

/**
 * Subtitles → Style: size, colour, bold, outline or dark box, and distance from the bottom. Kept
 * in the app's settings (the next video looks the same) and applied to the video right away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStyle(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).testTag("subtitle-style"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StyleChips(stringResource(R.string.player_style_size), AppSettings.SUBTITLE_SCALES.map { it to "${(it * 100).toInt()}%" }, settings.subtitleScale, "style-size") {
            onChange(settings.copy(subtitleScale = it))
        }
        StyleChips(stringResource(R.string.player_style_color), AppSettings.SUBTITLE_COLORS.zip(listOf(
            R.string.player_color_white, R.string.player_color_yellow, R.string.player_color_cyan, R.string.player_color_green,
        )).map { (c, n) -> c to stringResource(n) }, settings.subtitleColor, "style-color") { onChange(settings.copy(subtitleColor = it)) }
        StyleChips(stringResource(R.string.player_style_offset), AppSettings.SUBTITLE_OFFSETS.zip(listOf(
            R.string.player_offset_low, R.string.player_offset_normal, R.string.player_offset_raised, R.string.player_offset_high,
        )).map { (o, n) -> o to stringResource(n) }, settings.subtitleOffset, "style-offset") { onChange(settings.copy(subtitleOffset = it)) }
        StyleToggle(stringResource(R.string.player_style_bold), settings.subtitleBold, "style-bold") { onChange(settings.copy(subtitleBold = it)) }
        StyleToggle(stringResource(R.string.player_style_box), settings.subtitleBox, "style-box") { onChange(settings.copy(subtitleBox = it)) }
        TextButton(onClick = {
            onChange(settings.copy(subtitleScale = 1f, subtitleColor = AppSettings.SUBTITLE_COLORS.first(), subtitleOffset = 0.08f, subtitleBold = false, subtitleBox = false))
        }, modifier = Modifier.focusHighlight().testTag("style-reset")) { Text(stringResource(R.string.player_style_reset)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> StyleChips(title: String, options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) ->
                FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) },
                    leadingIcon = if (v == selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }) else null,
                    modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("$tag:$label"))
            }
        }
    }
}

@Composable
private fun StyleToggle(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) }.semantics { role = Role.Switch }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Swipe-to-seek (Nuvio's): how far a swipe across the whole width moves, by the video's length:
 * 60 s under 30 minutes, 90 s up to an hour, 120 s beyond (pure; tested).
 */
fun swipeSeekSpanMs(durationMs: Long): Long = when {
    durationMs < 30 * 60_000L -> 60_000L
    durationMs < 60 * 60_000L -> 90_000L
    else -> 120_000L
}

/** Where a swipe of [fraction] of the width (negative: left) lands, within the video (pure; tested). */
fun swipeSeekTarget(startMs: Long, durationMs: Long, fraction: Float): Long =
    (startMs + (swipeSeekSpanMs(durationMs) * fraction).toLong()).coerceIn(0L, durationMs.coerceAtLeast(0L))
