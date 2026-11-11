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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** The dock's tabs, in Nuvio's order. */
val DOCK_TABS: List<TopLevel> = listOf(TopLevel.HOME, TopLevel.SEARCH, TopLevel.LIBRARY, TopLevel.SETTINGS)

/** Height of the dock and its gap to the bottom edge: what lists keep clear (see LocalBottomClearance). */
val DOCK_HEIGHT = 68.dp
val DOCK_BOTTOM_GAP = 10.dp

/** The dock's height with or without the names under the icons (Settings → Appearance). */
fun dockHeight(labels: Boolean) = if (labels) DOCK_HEIGHT else 58.dp

/**
 * The phone's navigation, like Nuvio's: a frosted-glass pill floating over the content (no bar
 * around it), each tab an icon with its name underneath, the current one on a soft light pill.
 * Manga style: the same pill in paper and ink, names in comic lettering. A light tick on change.
 * Glass is drawn as a translucent tint, a sheen and a hairline edge: no backdrop blur, so it costs
 * nothing per frame while the lists behind it scroll.
 */
@Composable
fun Dock(current: TopLevel?, onSelect: (TopLevel) -> Unit, modifier: Modifier = Modifier) {
    val manga = LocalMangaStyle.current
    val labels = dev.yourstream.ui.screens.settings.LocalAppSettings.current.dockLabels
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(34.dp)
    val dark = colors.background.luminance() < 0.5f
    val tint = if (manga) colors.surfaceContainer.copy(alpha = 0.94f)
        else if (dark) Color(0xFF1E1E22).copy(alpha = 0.94f) else Color(0xFFF7F7FA).copy(alpha = 0.94f)
    val edge = if (manga) colors.onSurface else if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.35f), Color.Transparent))
    Row(
        modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = DOCK_BOTTOM_GAP).widthIn(max = 520.dp).fillMaxWidth()
            .height(dockHeight(labels))
            .shadow(if (manga) 0.dp else 18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape).background(tint).background(sheen)
            .border(if (manga) 2.dp else 1.dp, edge, shape)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag("nav:bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DOCK_TABS.forEach { t ->
            DockTab(t, selected = t == current, manga = manga, dark = dark, labels = labels, modifier = Modifier.weight(1f)) {
                if (t != current) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect(t)
            }
        }
    }
}

@Composable
private fun DockTab(t: TopLevel, selected: Boolean, manga: Boolean, dark: Boolean, labels: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pill = when {
        !selected -> Color.Transparent
        manga -> colors.onSurface
        dark -> Color.White.copy(alpha = 0.14f)
        else -> Color.Black.copy(alpha = 0.07f)
    }
    val on = when {
        manga && selected -> colors.surfaceContainer
        manga -> colors.onSurface
        selected -> colors.onSurface
        else -> colors.onSurfaceVariant.copy(alpha = 0.75f)
    }
    val fill by animateColorAsState(pill, label = "dock-pill")
    val tint by animateColorAsState(on, label = "dock-tint")
    val label = stringResource(t.label)
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(fill)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .focusHighlight(RoundedCornerShape(26.dp))
            .semantics { contentDescription = label }
            .testTag("nav:${t.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (labels) 24.dp else 26.dp))
        if (labels) {
            if (manga) Text(label.uppercase(), fontFamily = Manga.Lettering, fontSize = 13.sp, letterSpacing = 0.6.sp, maxLines = 1, color = tint)
            else Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, color = tint)
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

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
        val angle = spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        val number = remember { NumberFormat.getIntegerInstance() }
        val surfaceModifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth()
            .let { if (manga) it.padding(end = 3.dp, bottom = 3.dp).inkPanel(shape, shadow = 3.dp, border = 2.dp) else it }
            .clip(shape).clickable(onClick = onClick).testTag("indexing-pill")
        Surface(shape = shape, color = if (manga) colors.surfaceContainer else colors.inverseSurface,
            contentColor = if (manga) colors.onSurface else colors.inverseOnSurface, shadowElevation = if (manga) 0.dp else 6.dp, modifier = surfaceModifier) {
            Column {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = angle.value })
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
