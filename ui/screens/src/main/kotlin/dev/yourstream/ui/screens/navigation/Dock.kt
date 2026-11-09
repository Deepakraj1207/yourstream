package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.ui.core.LocalMangaStyle
import dev.yourstream.ui.core.Manga
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.inkPanel
import dev.yourstream.ui.screens.IndexingStatus
import dev.yourstream.ui.screens.R
import java.text.NumberFormat

/**
 * Whether the dock shows the current tab's name: folded away while the user scrolls down a list
 * (more room for content), back when they scroll up or switch tabs. Attach [connection] to the
 * content with `Modifier.nestedScroll`.
 */
@Stable
class DockState {
    var expanded by mutableStateOf(true)

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -SCROLL_SLOP) expanded = false else if (available.y > SCROLL_SLOP) expanded = true
            return Offset.Zero
        }
    }

    private companion object {
        const val SCROLL_SLOP = 2f
    }
}

/**
 * The phone's navigation as a floating dock, like Nuvio's: a capsule the width of its tabs,
 * centred above the bottom edge. Tabs are icons; the current one is a filled pill with its name,
 * which folds away while scrolling down (see [DockState]). Manga style: paper with an ink outline
 * and a hard shadow, the current tab inked, its name in comic lettering. A light tick on change.
 */
@Composable
fun Dock(current: TopLevel?, onSelect: (TopLevel) -> Unit, state: DockState, modifier: Modifier = Modifier) {
    val manga = LocalMangaStyle.current
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(current) { state.expanded = true }
    val capsule = CircleShape
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        val shell = if (manga) {
            Modifier.padding(end = 4.dp, bottom = 4.dp).inkPanel(capsule, shadow = 4.dp, border = 2.5.dp).clip(capsule).background(colors.surfaceContainer)
        } else {
            Modifier
        }
        val content: @Composable () -> Unit = {
            Row(
                Modifier.height(60.dp).padding(horizontal = 8.dp).testTag("nav:bar"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopLevel.entries.filter { it.compact }.forEach { t ->
                    DockTab(t, selected = t == current, expanded = state.expanded, manga = manga) {
                        if (t != current) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(t)
                    }
                }
            }
        }
        if (manga) Box(shell) { content() }
        else Surface(shape = capsule, color = colors.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 8.dp) { content() }
    }
}

@Composable
private fun DockTab(t: TopLevel, selected: Boolean, expanded: Boolean, manga: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fillTarget = when {
        !selected -> Color.Transparent
        manga -> colors.onSurface
        else -> colors.secondaryContainer
    }
    val tintTarget = when {
        !selected -> colors.onSurfaceVariant
        manga -> colors.surfaceContainer
        else -> colors.onSecondaryContainer
    }
    val fill by animateColorAsState(fillTarget, label = "dock-fill")
    val tint by animateColorAsState(if (manga && !selected) colors.onSurface else tintTarget, label = "dock-tint")
    val label = stringResource(t.label)
    Row(
        Modifier.height(46.dp).widthIn(min = 52.dp).clip(CircleShape).background(fill)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .focusHighlight(CircleShape)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp)
            .testTag("nav:${t.name}"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        AnimatedVisibility(
            visible = selected && expanded,
            enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 500f)) + fadeIn(tween(120)),
            exit = shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 600f)) + fadeOut(tween(90)),
        ) {
            if (manga) {
                Text(label.uppercase(), fontFamily = Manga.Lettering, fontSize = 16.sp, letterSpacing = 1.sp, maxLines = 1, color = tint,
                    modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = tint, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * What indexing is doing, floating above the dock on every tab while it runs: files found, a
 * real progress bar (Telegram's estimate of the chats' size), speed and time left. Tap: Library
 * status.
 */
@Composable
fun IndexingPill(status: IndexingStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = status.active, modifier = modifier,
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
    ) {
        val manga = LocalMangaStyle.current
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(18.dp)
        val spin = rememberInfiniteTransition(label = "indexing-spin")
        val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        val number = remember { NumberFormat.getIntegerInstance() }
        val surfaceModifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth()
            .let { if (manga) it.padding(end = 3.dp, bottom = 3.dp).inkPanel(shape, shadow = 3.dp, border = 2.dp) else it }
            .clip(shape).clickable(onClick = onClick).testTag("indexing-pill")
        Surface(shape = shape, color = if (manga) colors.surfaceContainer else colors.inverseSurface,
            contentColor = if (manga) colors.onSurface else colors.inverseOnSurface, shadowElevation = if (manga) 0.dp else 6.dp, modifier = surfaceModifier) {
            Column {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp).rotate(angle))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        val title = pluralStringResource(R.plurals.indexing_pill_found, status.found, number.format(status.found))
                        if (manga) Text(title.uppercase(), fontFamily = Manga.Lettering, fontSize = 15.sp, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(indexingDetail(status), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("indexing-detail"))
                    }
                    status.fraction?.let { f -> Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelLarge) }
                }
                val accent = if (manga) colors.tertiary else colors.inversePrimary
                val track = if (manga) colors.onSurface.copy(alpha = 0.12f) else colors.inverseOnSurface.copy(alpha = 0.2f)
                val f = status.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = accent, trackColor = track, drawStopIndicator = {}, gapSize = 0.dp)
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = accent, trackColor = track)
            }
        }
    }
}

/** "About 4 min left · 140/s · 2 chats · 1 waiting": what matters most first, for narrow phones. */
@Composable
private fun indexingDetail(s: IndexingStatus): String {
    val parts = mutableListOf<String>()
    s.etaSeconds?.let { parts += stringResource(R.string.indexing_pill_eta, durationLabel(it)) }
    s.perSecond?.let { parts += stringResource(R.string.indexing_pill_rate, it.toInt()) }
    parts += pluralStringResource(R.plurals.indexing_pill_chats, s.chats.coerceAtLeast(1), s.chats.coerceAtLeast(1))
    if (s.queued > 0) parts += pluralStringResource(R.plurals.indexing_pill_waiting, s.queued, s.queued)
    return parts.joinToString(" · ")
}

/** 40 → "1 min", 3_900 → "1 h 5 min" (short, rounded up to whole minutes). */
internal fun durationLabel(seconds: Long): String {
    val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
