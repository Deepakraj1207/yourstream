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

/**
 * The classic style's colours, as in the Android app's classic look: a black page, white text and
 * white buttons, greys for raised surfaces; a light page (white on black inverted) when dark is off.
 */
private val Black = Color(0xFF000000)
private val Raised = Color(0xFF141414)
private val RaisedHigh = Color(0xFF1F1F1F)
private val RaisedHighest = Color(0xFF2A2A2A)
private val White = Color(0xFFFFFFFF)
private val Accent = Color(0xFFE50914)

private val Dark = darkColorScheme(
    primary = White, onPrimary = Black,
    secondary = Accent, onSecondary = White,
    secondaryContainer = RaisedHighest, onSecondaryContainer = White,
    tertiary = Accent, onTertiary = White,
    background = Black, onBackground = White,
    surface = Black, onSurface = White,
    surfaceVariant = Raised, onSurfaceVariant = White.copy(alpha = 0.62f),
    surfaceContainerLowest = Black, surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Raised, surfaceContainerHigh = RaisedHigh, surfaceContainerHighest = RaisedHighest,
    inverseSurface = White, inverseOnSurface = Black,
    outline = White.copy(alpha = 0.28f), outlineVariant = White.copy(alpha = 0.12f),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Black, onPrimary = White,
    secondary = Accent, onSecondary = White,
    secondaryContainer = Color(0xFFE4E4E4), onSecondaryContainer = Black,
    tertiary = Accent, onTertiary = White,
    background = Color(0xFFF6F6F6), onBackground = Black,
    surface = Color(0xFFF6F6F6), onSurface = Black,
    surfaceVariant = Color(0xFFECECEC), onSurfaceVariant = Black.copy(alpha = 0.62f),
    surfaceContainerLowest = White, surfaceContainerLow = Color(0xFFF1F1F1),
    surfaceContainer = White, surfaceContainerHigh = Color(0xFFEAEAEA), surfaceContainerHighest = Color(0xFFDFDFDF),
    inverseSurface = Black, inverseOnSurface = White,
    outline = Black.copy(alpha = 0.3f), outlineVariant = Black.copy(alpha = 0.12f),
)

@Composable
fun YourStreamTheme(dark: Boolean, manga: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalMangaStyle provides manga) {
        if (manga) MaterialTheme(colorScheme = if (dark) Manga.ink else Manga.paper, shapes = Manga.shapes, content = content)
        else MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/** A small label on a poster or a version row: "4K", "1080p", "Telegram". */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, onColor: Color = MaterialTheme.colorScheme.onPrimary) {
    Box(modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = onColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
