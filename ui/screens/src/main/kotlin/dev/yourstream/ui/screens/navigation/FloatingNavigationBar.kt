package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.lerp
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.ui.core.LocalReducedMotion
import dev.yourstream.ui.core.focusHighlight

/** Height of the floating bar with its margins, for content that scrolls under it. */
val FloatingBarClearance: Dp = 64.dp + 24.dp

/**
 * Nuvio's floating navigation bar: a dark rounded pill held off the screen's edges over the
 * content, the current tab inside a lighter capsule, icons over names. [compact]: icons only in a
 * smaller pill (Adaptive while scrolling down, or "Always compact"). [glow]: a soft light around
 * the bar ("Glass glow"). Light theme: the same shape in the theme's surface colours.
 *
 * Motion as in Nuvio: one value, the label fraction (1 = names shown), eases over 300 ms
 * (cubic 0.2, 0, 0, 1) and drives everything together: the bar's height (58 → 64 dp; Nuvio goes to 48, but tabs here stay at least 48 dp to touch), its side
 * margins (58 → 28 dp), and the names fading while their line folds away. The capsule slides
 * between tabs on a spring; the glow fades in over 420 ms. Reduced motion: no animation.
 *
 * Tabs are full-height "tab"s with their selected state for screen readers; at least 48 dp wide.
 */
@Composable
fun FloatingNavigationBar(
    current: TopLevel?,
    onSelect: (TopLevel) -> Unit,
    labels: Boolean,
    glow: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    val dark = c.surface.luminance() < 0.5f
    val reduced = LocalReducedMotion.current
    val shape = RoundedCornerShape(36.dp)
    val container = if (dark) Color(0xFF1C1C1E).copy(alpha = 0.96f) else c.surfaceContainerHigh.copy(alpha = 0.97f)
    val edge = if (dark) Color.White.copy(alpha = 0.08f) else c.outlineVariant.copy(alpha = 0.6f)
    val f by animateFloatAsState(if (compact) 0f else 1f, if (reduced) snap() else tween(LABEL_MS, easing = NuvioEasing), label = "bar-labels")
    val glowAlpha by animateFloatAsState(if (glow) 0.5f else 0f, if (reduced) snap() else tween(GLOW_MS), label = "bar-glow")
    val selected = MAIN_TABS.indexOf(current?.tab)
    Box(
        modifier.fillMaxWidth()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = lerp(58.dp, 28.dp, f), vertical = 12.dp)
            .testTag("nav:bar"),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 400.dp).fillMaxWidth()
                .height(lerp(COMPACT_HEIGHT, 64.dp, f))
                .then(if (glow) Modifier.testTag("nav:glow") else Modifier)
                .shadow(lerp(10.dp, 24.dp, glowAlpha * 2f), shape,
                    ambientColor = lerp(Color.Black, Color.White.copy(alpha = 0.5f), glowAlpha * 2f),
                    spotColor = lerp(Color.Black, Color.White.copy(alpha = 0.5f), glowAlpha * 2f))
                .clip(shape)
                .background(container)
                .border(1.dp, edge, shape)
                .padding(5.dp)
                .testTag(if (compact) "nav:compact" else "nav:expanded"),
        ) {
            val tab = maxWidth / MAIN_TABS.size
            if (selected >= 0) {
                // One capsule that slides to the chosen tab.
                val x by animateDpAsState(tab * selected, if (reduced) snap() else spring(dampingRatio = 0.8f, stiffness = 380f), label = "bar-capsule")
                Box(Modifier.offset(x = x).width(tab).fillMaxHeight().clip(RoundedCornerShape(30.dp))
                    .background(if (dark) Color.White.copy(alpha = 0.16f) else c.onSurface.copy(alpha = 0.09f)).testTag("nav:capsule"))
            }
            Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                MAIN_TABS.forEachIndexed { i, t ->
                    FloatingTab(t, i == selected, if (labels) f else 0f, dark, Modifier.weight(1f).fillMaxHeight()) { onSelect(t) }
                }
            }
        }
    }
}

/** [label]: how much of the name shows (1 shown, 0 folded away; the bar animates it). */
@Composable
private fun FloatingTab(t: TopLevel, selected: Boolean, label: Float, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val target = when {
        selected -> if (dark) Color.White else c.onSurface
        else -> if (dark) Color(0xFF9E9EA3) else c.onSurfaceVariant
    }
    val content by animateColorAsState(target, if (reduced) snap() else tween(180), label = "tab-content")
    val name = stringResource(t.label)
    val showsName = label > 0.5f
    Box(
        modifier.clip(RoundedCornerShape(30.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .focusHighlight()
            .testTag("nav:${t.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // Without its name on screen, the icon carries it for screen readers.
            Icon(t.icon, contentDescription = if (showsName) null else name, tint = content, modifier = Modifier.size(lerp(26.dp, 24.dp, label)))
            if (label > 0f) {
                Text(name, color = content, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(top = (2 * label).dp).foldHeight(label).graphicsLayer { alpha = label }
                        .then(if (showsName) Modifier else Modifier.clearAndSetSemantics { }))
            }
        }
    }
}

/** Lays the content out at [fraction] of its height (the name's line folds away with the label). */
private fun Modifier.foldHeight(fraction: Float) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val h = (p.height * fraction).roundToInt()
    layout(p.width, h) { p.place(0, h - p.height) }
}

private val NuvioEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val LABEL_MS = 300
private const val GLOW_MS = 420
/** 48 dp tabs plus the 5 dp inner margin: the touch-target minimum holds while compact too. */
private val COMPACT_HEIGHT = 58.dp
