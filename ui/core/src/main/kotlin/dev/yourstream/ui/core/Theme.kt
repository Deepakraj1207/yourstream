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

/** Brand palette: cinematic dark with a warm amber accent; a matching light scheme. */
object Brand {
    val Amber = Color(0xFFF2B84B)
    val AmberDark = Color(0xFF7A5300)
    val Teal = Color(0xFF63D2C6)
    val Ink = Color(0xFF0E1015)
    val Night = Color(0xFF151821)
    val NightHigh = Color(0xFF1E2230)
    val NightHigher = Color(0xFF272C3C)
    val Mist = Color(0xFFE7E9F0)
    val Fog = Color(0xFFAAB0C0)

    val dark: ColorScheme = darkColorScheme(
        primary = Amber, onPrimary = Color(0xFF2B1D00), primaryContainer = Color(0xFF4A3300), onPrimaryContainer = Color(0xFFFFDEA6),
        secondary = Teal, onSecondary = Color(0xFF00201D), secondaryContainer = Color(0xFF0F3B37), onSecondaryContainer = Color(0xFFB4F0E8),
        background = Ink, onBackground = Mist, surface = Ink, onSurface = Mist,
        surfaceVariant = NightHigh, onSurfaceVariant = Fog, surfaceContainerLowest = Ink, surfaceContainerLow = Night,
        surfaceContainer = Night, surfaceContainerHigh = NightHigh, surfaceContainerHighest = NightHigher,
        outline = Color(0xFF4A5064), outlineVariant = Color(0xFF2E3344), error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    )

    val light: ColorScheme = lightColorScheme(
        primary = AmberDark, onPrimary = Color.White, primaryContainer = Color(0xFFFFDEA6), onPrimaryContainer = Color(0xFF271900),
        secondary = Color(0xFF00696F), onSecondary = Color.White, secondaryContainer = Color(0xFFB4F0E8), onSecondaryContainer = Color(0xFF00201D),
        background = Color(0xFFFAF8F4), onBackground = Color(0xFF1C1B18), surface = Color(0xFFFAF8F4), onSurface = Color(0xFF1C1B18),
        surfaceVariant = Color(0xFFEDE6DA), onSurfaceVariant = Color(0xFF4D4639),
        surfaceContainerLow = Color(0xFFF4F1EB), surfaceContainer = Color(0xFFEEEBE4), surfaceContainerHigh = Color(0xFFE8E5DE),
        surfaceContainerHighest = Color(0xFFE2DFD8), outline = Color(0xFF7F7667), outlineVariant = Color(0xFFD0C5B4),
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
fun typographyFor(layout: WindowLayout): Typography {
    val base = Typography()
    val t = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
    )
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
 * layouts follow the system setting.
 */
@Composable
fun YourStreamTheme(layout: WindowLayout, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val dark = layout.isTv || darkTheme
    CompositionLocalProvider(LocalWindowLayout provides layout, LocalDimens provides dimensFor(layout)) {
        MaterialTheme(colorScheme = if (dark) Brand.dark else Brand.light, typography = typographyFor(layout), shapes = BrandShapes, content = content)
    }
}
