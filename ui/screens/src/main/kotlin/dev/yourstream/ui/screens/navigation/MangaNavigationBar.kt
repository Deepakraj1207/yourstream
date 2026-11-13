package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.ui.core.LocalReducedMotion
import dev.yourstream.ui.core.focusHighlight

/**
 * The manga style's navigation: a printed strip. Dark (or paper) ground with an ink rule along
 * the top; each tab an icon over its name in bold lettering; the current tab a solid block of
 * ink with a hard offset shadow and an inked border, like a stamped panel. Pressing a tab pushes
 * its block down onto its shadow (no movement with reduced motion).
 *
 * Every tab is a full-height "tab" with its selected state for screen readers, at least 48 dp
 * wide and 64 dp tall.
 */
@Composable
fun MangaNavigationBar(current: TopLevel?, onSelect: (TopLevel) -> Unit, labels: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ground = colors.surface
    val ink = colors.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .background(ground)
            .drawBehind { drawRect(ink, size = Size(size.width, 2.dp.toPx())) }
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .height(76.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .selectableGroup()
            .testTag("nav:bar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MAIN_TABS.forEach { t ->
            MangaTab(t, selected = t == current?.tab, labels = labels, modifier = Modifier.weight(1f).fillMaxHeight()) { onSelect(t) }
        }
    }
}

@Composable
private fun MangaTab(t: TopLevel, selected: Boolean, labels: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val ground = colors.surface
    val dark = ground.luminance() < 0.5f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    // The block sits 3 dp above its shadow and lands on it while pressed.
    val lift = if (selected && !pressed) 3.dp else 0.dp
    val shown by animateDpAsState(lift, if (reduced) spring(stiffness = 1e6f) else spring(stiffness = 900f), label = "tab-lift")
    // Dark: a cream block on its black shadow. Light: a paper panel with an ink border and ink
    // shadow, like the manga style's cards.
    val shadow = if (dark) Color.Black.copy(alpha = 0.55f) else ink
    val fill = if (dark) ink else colors.surfaceContainerLowest
    val content = when {
        !selected -> colors.onSurfaceVariant
        dark -> ground
        else -> ink
    }
    val label = stringResource(t.label)
    Box(
        modifier
            .selectable(selected = selected, onClick = onClick, role = Role.Tab, interactionSource = interaction, indication = null)
            .focusHighlight()
            .testTag("nav:${t.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight()
                .padding(end = 3.dp, bottom = 3.dp)
                .drawBehind {
                    if (!selected) return@drawBehind
                    val off = 3.dp.toPx()
                    drawRect(shadow, topLeft = Offset(off, off), size = size)
                }
                .offset(x = 3.dp - shown, y = 3.dp - shown)
                .drawBehind {
                    if (selected) {
                        drawRect(fill)
                        drawRect(if (dark) Color.Black else ink, style = Stroke(2.dp.toPx()))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val showName = labels || selected
                // Without its name on screen, the icon carries it for screen readers.
                Icon(t.icon, contentDescription = if (showName) null else label, tint = content, modifier = Modifier.size(24.dp))
                if (showName) {
                    Text(
                        label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, fontWeight = if (selected) FontWeight.Black else FontWeight.Bold, letterSpacing = 0.2.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
