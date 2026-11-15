package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.animateColorAsState
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
    val height by animateDpAsState(if (compact) 56.dp else 64.dp, if (reduced) snap() else spring(stiffness = 600f), label = "bar-height")
    val tabWidth = 64.dp
    Box(
        modifier.fillMaxWidth()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("nav:bar"),
        contentAlignment = Alignment.Center,
    ) {
        val width by animateDpAsState(if (compact) tabWidth * MAIN_TABS.size + 12.dp else 1000.dp, if (reduced) snap() else spring(stiffness = 600f), label = "bar-width")
        Row(
            Modifier
                .then(if (compact) Modifier.width(width) else Modifier.fillMaxWidth())
                .height(height)
                .then(
                    if (glow) Modifier.shadow(24.dp, shape, ambientColor = Color.White.copy(alpha = 0.5f), spotColor = Color.White.copy(alpha = 0.5f)).testTag("nav:glow")
                    else Modifier.shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black),
                )
                .clip(shape)
                .background(container)
                .border(1.dp, edge, shape)
                .padding(6.dp)
                .selectableGroup()
                .testTag(if (compact) "nav:compact" else "nav:expanded"),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MAIN_TABS.forEach { t ->
                FloatingTab(t, t == current?.tab, labels && !compact, dark, Modifier.weight(1f).fillMaxHeight()) { onSelect(t) }
            }
        }
    }
}

@Composable
private fun FloatingTab(t: TopLevel, selected: Boolean, showLabel: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val capsule = if (dark) Color.White.copy(alpha = 0.16f) else c.onSurface.copy(alpha = 0.09f)
    val fill by animateColorAsState(if (selected) capsule else Color.Transparent, if (reduced) snap() else tween(180), label = "tab-fill")
    val content = when {
        selected -> if (dark) Color.White else c.onSurface
        else -> if (dark) Color(0xFF9E9EA3) else c.onSurfaceVariant
    }
    val label = stringResource(t.label)
    Box(
        modifier.clip(RoundedCornerShape(30.dp)).background(fill)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .focusHighlight()
            .testTag("nav:${t.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // Without its name on screen, the icon carries it for screen readers.
            Icon(t.icon, contentDescription = if (showLabel) null else label, tint = content, modifier = Modifier.size(if (showLabel) 24.dp else 26.dp))
            if (showLabel) Text(label, color = content, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
