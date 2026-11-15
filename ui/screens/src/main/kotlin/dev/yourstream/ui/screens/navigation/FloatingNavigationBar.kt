package dev.yourstream.ui.screens.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
 * The floating navigation bar (Settings → Layout → Navigation bar → Floating): a rounded bar held
 * off the screen's edges, the current tab a filled capsule around its icon and name. [glow] adds a
 * soft light in the accent colour around the bar. Tabs are full-height "tab"s with their selected
 * state for screen readers, at least 48 dp wide.
 */
@Composable
fun FloatingNavigationBar(current: TopLevel?, onSelect: (TopLevel) -> Unit, labels: Boolean, glow: Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier.fillMaxWidth()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("nav:bar"),
    ) {
        Surface(
            shape = shape, color = c.surfaceContainerHigh, tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
                .then(if (glow) Modifier.shadow(22.dp, shape, ambientColor = c.primary, spotColor = c.primary).testTag("nav:glow") else Modifier.shadow(6.dp, shape)),
        ) {
            Row(
                Modifier.fillMaxWidth().height(68.dp).padding(6.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                MAIN_TABS.forEach { t -> FloatingTab(t, t == current?.tab, labels, Modifier.weight(1f).fillMaxHeight()) { onSelect(t) } }
            }
        }
    }
}

@Composable
private fun FloatingTab(t: TopLevel, selected: Boolean, labels: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val fill by animateColorAsState(if (selected) c.secondaryContainer else Color.Transparent, if (reduced) snap() else tween(180), label = "floating-tab")
    val content = if (selected) c.onSecondaryContainer else c.onSurfaceVariant
    val label = stringResource(t.label)
    val showName = labels || selected
    Box(
        modifier.clip(RoundedCornerShape(26.dp)).background(fill)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .focusHighlight()
            .testTag("nav:${t.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(t.icon, contentDescription = if (showName) null else label, tint = content, modifier = Modifier.size(24.dp))
            if (showName) Text(label, color = content, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
