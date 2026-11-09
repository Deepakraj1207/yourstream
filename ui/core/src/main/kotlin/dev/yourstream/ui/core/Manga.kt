package dev.yourstream.ui.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

/**
 * The manga / comic-store look: black ink on newsprint paper (or paper-white chalk on ink at
 * night), comic lettering for headings, panels with thick ink borders and hard offset shadows,
 * halftone screentones and speed lines. One manga-red accent, used sparingly.
 */
object Manga {
    val Paper = Color(0xFFF4EFE4)
    val PaperBright = Color(0xFFFFFCF5)
    val Ink = Color(0xFF141414)
    val Red = Color(0xFFD7263D)

    /** Bangers (SIL OFL): comic-book lettering. Single weight; never synthesised bolder. */
    val Lettering = FontFamily(Font(R.font.bangers, FontWeight.Normal))

    val paper: ColorScheme = lightColorScheme(
        primary = Ink, onPrimary = Paper, primaryContainer = Color(0xFFE6E0D2), onPrimaryContainer = Ink,
        secondary = Color(0xFF3A362F), onSecondary = Paper,
        // Selected tabs and chips are inverted: ink panels with paper text.
        secondaryContainer = Ink, onSecondaryContainer = Paper,
        tertiary = Red, onTertiary = Color.White,
        background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
        surfaceVariant = Color(0xFFE6E0D2), onSurfaceVariant = Color(0xFF4A453C),
        surfaceContainerLowest = PaperBright, surfaceContainerLow = Color(0xFFFAF6EE), surfaceContainer = PaperBright,
        surfaceContainerHigh = Color(0xFFEFE9DC), surfaceContainerHighest = Color(0xFFE6E0D2),
        inverseSurface = Ink, inverseOnSurface = Paper, inversePrimary = Paper,
        // Outlines are ink lines: text fields, chips and outlined buttons look hand-inked.
        outline = Ink, outlineVariant = Color(0xFFBDB6A6), error = Color(0xFFB3261E), onError = Color.White,
    )

    val ink: ColorScheme = darkColorScheme(
        primary = Color(0xFFF2EDE2), onPrimary = Ink, primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color(0xFFF2EDE2),
        secondary = Color(0xFFCFC8BA), onSecondary = Ink,
        secondaryContainer = Color(0xFFF2EDE2), onSecondaryContainer = Ink,
        tertiary = Color(0xFFFF4D5E), onTertiary = Ink,
        background = Color(0xFF121212), onBackground = Color(0xFFF2EDE2), surface = Color(0xFF121212), onSurface = Color(0xFFF2EDE2),
        surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFB8B2A6),
        surfaceContainerLowest = Color(0xFF0C0C0C), surfaceContainerLow = Color(0xFF171717), surfaceContainer = Color(0xFF1B1B1B),
        surfaceContainerHigh = Color(0xFF232323), surfaceContainerHighest = Color(0xFF2B2B2B),
        inverseSurface = Color(0xFFF2EDE2), inverseOnSurface = Ink, inversePrimary = Ink,
        outline = Color(0xFFF2EDE2), outlineVariant = Color(0xFF3A3A3A), error = Color(0xFFFF8A80), onError = Ink,
    )

    /** Panel corners: nearly square, like frames on a manga page. */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(12.dp),
    )

    /** Comic lettering for display, headline and large titles; body text stays easy to read. */
    fun typography(base: Typography): Typography {
        fun letter(t: TextStyle, spacing: Float = 1f) = t.copy(fontFamily = Lettering, fontWeight = FontWeight.Normal, letterSpacing = spacing.sp)
        return base.copy(
            displayLarge = letter(base.displayLarge), displayMedium = letter(base.displayMedium), displaySmall = letter(base.displaySmall),
            headlineLarge = letter(base.headlineLarge), headlineMedium = letter(base.headlineMedium), headlineSmall = letter(base.headlineSmall),
            titleLarge = letter(base.titleLarge, 0.8f),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** True when the manga style is on (components add ink borders, shadows, screentones). */
val LocalMangaStyle = staticCompositionLocalOf { false }

/**
 * A manga panel: thick ink border and a hard, offset ink shadow (no blur). Put it before `clip`
 * so the shadow is drawn outside the panel. No-op in the classic style.
 */
fun Modifier.inkPanel(shape: Shape? = null, shadow: Dp = 4.dp, border: Dp = 2.dp): Modifier = composed {
    if (!LocalMangaStyle.current) return@composed this
    val ink = MaterialTheme.colorScheme.onSurface
    val s = shape ?: MaterialTheme.shapes.medium
    this.drawWithContent {
        val outline = s.createOutline(size, layoutDirection, this)
        val off = shadow.toPx()
        translate(off, off) { drawOutline(outline, ink) }
        drawContent()
        drawOutline(outline, ink, style = Stroke(border.toPx()))
    }
}

/**
 * Screentone: a grid of halftone dots that grow towards one corner, like the shading in a manga.
 * [seed] varies the direction per title so neighbouring placeholders differ.
 */
fun Modifier.screentone(color: Color, seed: Int = 0, spacing: Dp = 7.dp, maxRadius: Dp = 2.6.dp): Modifier = drawBehind {
    val step = spacing.toPx()
    val rMax = maxRadius.toPx()
    val fromLeft = seed % 2 == 0
    val fromTop = (seed / 2) % 2 == 0
    var y = 0f
    var row = 0
    while (y < size.height + step) {
        var x = if (row % 2 == 0) 0f else step / 2
        while (x < size.width + step) {
            val fx = if (fromLeft) x / size.width else 1 - x / size.width
            val fy = if (fromTop) y / size.height else 1 - y / size.height
            val r = rMax * ((fx + fy) / 2f).coerceIn(0.08f, 1f)
            drawCircle(color, r, Offset(x, y))
            x += step
        }
        y += step * 0.87f
        row++
    }
}

/** Speed lines radiating from the centre, like an action panel. */
fun Modifier.speedLines(color: Color, lines: Int = 56, seed: Int = 0): Modifier = drawBehind {
    val c = Offset(size.width * 0.62f, size.height * 0.45f)
    val reach = size.width.coerceAtLeast(size.height) * 1.2f
    for (i in 0 until lines) {
        val jitter = ((i * 7919 + seed) % 13) / 13f
        val a = (i + jitter) / lines * (2 * Math.PI).toFloat()
        val inner = size.minDimension * (0.22f + 0.18f * jitter)
        val start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner)
        val end = Offset(c.x + cos(a) * reach, c.y + sin(a) * reach)
        drawLine(color, start, end, strokeWidth = 1.2f + 2.8f * jitter)
    }
}

/** A missing poster in the manga style: screentone shading and the title's initials in comic lettering. */
@Composable
fun MangaTitleArt(title: String, modifier: Modifier = Modifier, showInitials: Boolean = true) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val paper = if (dark) Color(0xFF1E1E1E) else Manga.PaperBright
    val ink = if (dark) Color(0xFFF2EDE2) else Manga.Ink
    val seed = remember(title) { title.lowercase().hashCode().absoluteValue }
    Box(modifier.background(paper).screentone(ink.copy(alpha = 0.22f), seed), contentAlignment = Alignment.Center) {
        if (showInitials) Text(
            initials(title), fontFamily = Manga.Lettering, color = ink, textAlign = TextAlign.Center, maxLines = 1,
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = Manga.Lettering, letterSpacing = 2.sp),
        )
    }
}

/** A short ink rule under a section title, like a brush stroke. */
fun Modifier.inkUnderline(color: Color, thickness: Dp = 3.dp, fraction: Float = 0.45f): Modifier = drawBehind {
    val t = thickness.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - t), size = Size(size.width * fraction, t))
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
