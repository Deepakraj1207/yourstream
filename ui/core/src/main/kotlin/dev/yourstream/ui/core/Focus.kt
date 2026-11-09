package dev.yourstream.ui.core

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Visible focus for keyboard and remote navigation: an accent border, and on TV/large screens a
 * slight lift. Touch never moves focus to clickables, so touch users do not see it. Apply before
 * `clickable`.
 */
fun Modifier.focusHighlight(shape: Shape? = null): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) LocalDimens.current.focusScale else 1f, label = "focus-scale")
    val color = MaterialTheme.colorScheme.primary
    val s = shape ?: MaterialTheme.shapes.medium
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .then(if (focused) Modifier.border(3.dp, color, s) else Modifier)
}
