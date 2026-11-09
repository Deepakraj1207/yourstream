package dev.yourstream.ui.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Plain Material 3 theme (visual polish comes later). TV is always dark: living rooms, and
 * focus highlights read better on dark surfaces.
 */
@Composable
fun YourStreamTheme(layout: WindowLayout, content: @Composable () -> Unit) {
    val dark = layout.isTv || isSystemInDarkTheme()
    CompositionLocalProvider(LocalWindowLayout provides layout) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
    }
}
