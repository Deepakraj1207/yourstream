package dev.yourstream.ui.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.absoluteValue

/**
 * Artwork for a title: the poster from matched metadata when there is one, otherwise (and while it
 * loads, or if it fails) a placeholder derived from the real title — colour from a hash of the
 * title, its initials. No stock or demo images. Decorative: the card carries the description.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** False where the title is already drawn over the art (wide cards, hero background). */
    showInitials: Boolean = true,
) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier) {
        TitleArt(title, Modifier.fillMaxSize(), showInitials)
        if (url != null && !failed) {
            AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize(), onError = { failed = true })
        }
    }
}

/** A typographic placeholder: two-tone gradient picked from the title, with its initials. */
@Composable
fun TitleArt(title: String, modifier: Modifier = Modifier, showInitials: Boolean = true) {
    val (a, b) = remember(title) { TitleColors.of(title) }
    Box(modifier.background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        if (showInitials) Text(
            initials(title), color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** Initials of the first two significant words ("The Dark Knight" → "DK"). */
fun initials(title: String): String {
    val words = title.split(' ', '.', '-', ':', '_').filter { it.isNotBlank() && it.lowercase() !in setOf("the", "a", "an", "of", "and") }
    return words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }.joinToString("").ifEmpty { title.take(1).uppercase() }
}

object TitleColors {
    private val palette = listOf(
        Color(0xFF7A4A2A) to Color(0xFF2B1A12), Color(0xFF2E5A7A) to Color(0xFF10202E), Color(0xFF5A2E7A) to Color(0xFF1F102E),
        Color(0xFF2E7A5E) to Color(0xFF0F2A21), Color(0xFF7A2E3E) to Color(0xFF2A1015), Color(0xFF6A6A2E) to Color(0xFF26260F),
        Color(0xFF2E3E7A) to Color(0xFF10152A), Color(0xFF7A5A2E) to Color(0xFF2A1F10),
    )

    /** Stable per title (the same title always gets the same colours). */
    fun of(title: String): Pair<Color, Color> = palette[title.lowercase().hashCode().absoluteValue % palette.size]
}

/** Darkens the bottom of artwork so text over it stays readable (contrast ≥ 4.5:1 on white text). */
val BottomScrim: Brush = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.85f))

/**
 * A thin, continuous watch-progress line (no gap or stop dot, unlike the M3 default), so a partly
 * watched title reads as one bar at a glance.
 */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, trackColor: Color = Color.White.copy(alpha = 0.25f)) {
    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.height(4.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = trackColor,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
