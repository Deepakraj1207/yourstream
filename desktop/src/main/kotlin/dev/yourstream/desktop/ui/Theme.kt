package dev.yourstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's colours: warm paper on ink, like the Android app's classic style. */
private val Ink = Color(0xFF161310)
private val InkRaised = Color(0xFF221E1A)
private val InkHigh = Color(0xFF2E2924)
private val Paper = Color(0xFFE8E2D4)
private val Accent = Color(0xFFE50914)

private val Dark = darkColorScheme(
    primary = Paper, onPrimary = Ink,
    secondary = Accent, onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A4038), onSecondaryContainer = Paper,
    background = Ink, onBackground = Paper,
    surface = Ink, onSurface = Paper,
    surfaceVariant = InkRaised, onSurfaceVariant = Paper.copy(alpha = 0.72f),
    surfaceContainer = InkRaised, surfaceContainerHigh = InkHigh, surfaceContainerHighest = InkHigh,
    outline = Paper.copy(alpha = 0.28f), outlineVariant = Paper.copy(alpha = 0.14f),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    secondary = Accent, onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9BFA8), onSecondaryContainer = Ink,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFDCD5C5), onSurfaceVariant = Ink.copy(alpha = 0.72f),
    surfaceContainer = Color(0xFFDCD5C5), surfaceContainerHigh = Color(0xFFD2CAB8), surfaceContainerHighest = Color(0xFFD2CAB8),
    outline = Ink.copy(alpha = 0.3f), outlineVariant = Ink.copy(alpha = 0.15f),
)

@Composable
fun YourStreamTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** A small label on a poster or a version row: "4K", "1080p", "Telegram". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, onColor: Color = MaterialTheme.colorScheme.onPrimary) {
    Box(modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = onColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
