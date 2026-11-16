package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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

/**
 * The manga style's navigation, laid out like a comic page: a thick ink rule along the top, and
 * each tab a slanted panel with an inked border and white gutters between panels. The panels sit
 * slightly out of line, as on a hand-drawn page. The current tab's panel is filled with ink and
 * its icon and name are printed in the paper colour; pressing a tab tints its panel. Colours
 * change instantly with reduced motion.
 *
 * Only the panel outline is slanted; the icon and name stay upright so they read normally. Every
 * tab is a full-height "tab" with its selected state for screen readers, at least 48 dp wide and
 * 60 dp tall.
 */
@Composable
fun MangaNavigationBar(current: TopLevel?, onSelect: (TopLevel) -> Unit, labels: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind { drawRect(ink, size = Size(size.width, 3.dp.toPx())) }
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .height(76.dp)
            .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 7.dp)
            .selectableGroup()
            .testTag("nav:bar"),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MAIN_TABS.forEachIndexed { i, t ->
            MangaTab(
                t, selected = t == current?.tab, labels = labels, nudge = NUDGES[i % NUDGES.size],
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { onSelect(t) }
        }
    }
}

/** How far each panel sits off the line (up is negative), so the strip looks hand-ruled. */
private val NUDGES = listOf(0.dp, (-2).dp, 1.dp, 0.dp)

/** The panel's slant: the top edge leans right of the bottom edge by this share of its height (about 8°). */
private const val SLANT = 0.14f

@Composable
private fun MangaTab(t: TopLevel, selected: Boolean, labels: Boolean, nudge: Dp, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    val paper = colors.surface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val target = when {
        selected -> ink
        pressed -> ink.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val fill by animateColorAsState(target, if (reduced) snap() else tween(140), label = "panel-fill")
    val content by animateColorAsState(if (selected) paper else ink, if (reduced) snap() else tween(140), label = "panel-content")
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
                .offset(y = nudge)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    val half = stroke / 2
                    val lean = size.height * SLANT
                    val panel = Path().apply {
                        moveTo(lean + half, half)
                        lineTo(size.width - half, half)
                        lineTo(size.width - lean - half, size.height - half)
                        lineTo(half, size.height - half)
                        close()
                    }
                    if (fill.alpha > 0f) drawPath(panel, fill)
                    drawPath(panel, ink, style = Stroke(stroke))
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
                        modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
                    )
                }
            }
        }
    }
}
