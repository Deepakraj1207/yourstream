package dev.yourstream.ui.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Brand palette: monochrome. Black, white and greys only; the one exception is errors, which keep
 * a muted red so a failure is noticed at a glance (the message says what it is too).
 */
object Brand {
    val Black = Color(0xFF0A0A0A)
    val White = Color(0xFFFAFAFA)

    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFF5F5F5), onPrimary = Color(0xFF0A0A0A), primaryContainer = Color(0xFF2E2E2E), onPrimaryContainer = Color(0xFFF5F5F5),
        secondary = Color(0xFFBDBDBD), onSecondary = Color(0xFF0A0A0A), secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color(0xFFEDEDED),
        tertiary = Color(0xFFD6D6D6), onTertiary = Color(0xFF0A0A0A),
        background = Black, onBackground = Color(0xFFEDEDED), surface = Black, onSurface = Color(0xFFEDEDED),
        surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFA6A6A6),
        surfaceContainerLowest = Color(0xFF050505), surfaceContainerLow = Color(0xFF121212), surfaceContainer = Color(0xFF161616),
        surfaceContainerHigh = Color(0xFF1E1E1E), surfaceContainerHighest = Color(0xFF282828),
        inverseSurface = Color(0xFFEDEDED), inverseOnSurface = Color(0xFF161616), inversePrimary = Color(0xFF2E2E2E),
        outline = Color(0xFF5E5E5E), outlineVariant = Color(0xFF333333), error = Color(0xFFE6A0A0), onError = Color(0xFF2B0000),
    )

    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF111111), onPrimary = White, primaryContainer = Color(0xFFE0E0E0), onPrimaryContainer = Color(0xFF111111),
        secondary = Color(0xFF4A4A4A), onSecondary = White, secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color(0xFF1A1A1A),
        tertiary = Color(0xFF333333), onTertiary = White,
        background = White, onBackground = Color(0xFF141414), surface = White, onSurface = Color(0xFF141414),
        surfaceVariant = Color(0xFFE8E8E8), onSurfaceVariant = Color(0xFF555555),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F4F4), surfaceContainer = Color(0xFFEFEFEF),
        surfaceContainerHigh = Color(0xFFE9E9E9), surfaceContainerHighest = Color(0xFFE3E3E3),
        inverseSurface = Color(0xFF1E1E1E), inverseOnSurface = Color(0xFFF0F0F0), inversePrimary = Color(0xFFE0E0E0),
        outline = Color(0xFF8A8A8A), outlineVariant = Color(0xFFCFCFCF), error = Color(0xFF9A2B2B), onError = White,
    )
}

/** Sizes that change with the layout (one place, so every component scales together). */
@Immutable
data class Dimens(
    val gutter: Dp,
    val posterWidth: Dp,
    val wideCardWidth: Dp,
    val heroHeight: Dp,
    val rowGap: Dp,
    val cardGap: Dp,
    /** Focus scale for cards: TV lifts the focused card, touch layouts do not. */
    val focusScale: Float,
)

val LocalDimens = staticCompositionLocalOf { dimensFor(WindowLayout.COMPACT) }

fun dimensFor(layout: WindowLayout): Dimens = when (layout) {
    WindowLayout.COMPACT -> Dimens(16.dp, 116.dp, 248.dp, 280.dp, 20.dp, 12.dp, 1f)
    WindowLayout.MEDIUM -> Dimens(24.dp, 140.dp, 300.dp, 340.dp, 24.dp, 14.dp, 1f)
    WindowLayout.EXPANDED -> Dimens(32.dp, 160.dp, 336.dp, 400.dp, 28.dp, 16.dp, 1.03f)
    WindowLayout.TV -> Dimens(48.dp, 168.dp, 320.dp, 360.dp, 28.dp, 20.dp, 1.08f)
}

private fun scale(t: TextStyle, by: Float) = t.copy(fontSize = t.fontSize * by, lineHeight = if (t.lineHeight == TextUnit.Unspecified) t.lineHeight else t.lineHeight * by)

/** Material type scale with heavier titles; TV is ~15 % larger (10-foot viewing). */
fun typographyFor(layout: WindowLayout, manga: Boolean = false): Typography {
    val base = Typography()
    val plain = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
    )
    val t = if (manga) Manga.typography(plain) else plain
    if (!layout.isTv) return t
    val k = 1.15f
    return Typography(
        displayLarge = scale(t.displayLarge, k), displayMedium = scale(t.displayMedium, k), displaySmall = scale(t.displaySmall, k),
        headlineLarge = scale(t.headlineLarge, k), headlineMedium = scale(t.headlineMedium, k), headlineSmall = scale(t.headlineSmall, k),
        titleLarge = scale(t.titleLarge, k), titleMedium = scale(t.titleMedium, k), titleSmall = scale(t.titleSmall, k),
        bodyLarge = scale(t.bodyLarge, k), bodyMedium = scale(t.bodyMedium, k), bodySmall = scale(t.bodySmall, k),
        labelLarge = scale(t.labelLarge, k), labelMedium = scale(t.labelMedium, k), labelSmall = scale(t.labelSmall, k),
    )
}

val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The app theme. TV is always dark (living rooms; focus reads best on dark surfaces); other
 * layouts follow the system setting. [manga]: the manga / comic-store style ([Manga]): paper and
 * ink palettes, comic lettering, panel shapes; components add ink borders and screentones.
 */
@Composable
fun YourStreamTheme(layout: WindowLayout, darkTheme: Boolean = isSystemInDarkTheme(), manga: Boolean = false, content: @Composable () -> Unit) {
    val dark = layout.isTv || darkTheme
    val scheme = when {
        manga && dark -> Manga.ink
        manga -> Manga.paper
        dark -> Brand.dark
        else -> Brand.light
    }
    CompositionLocalProvider(LocalWindowLayout provides layout, LocalDimens provides dimensFor(layout), LocalMangaStyle provides manga) {
        MaterialTheme(colorScheme = scheme, typography = typographyFor(layout, manga), shapes = if (manga) Manga.shapes else BrandShapes, content = content)
    }
}
