package dev.yourstream.ui.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The layout class the app adapts to. Decided from the width (Material window size classes)
 * and whether this is a TV (D-pad first, 10-foot UI, no touch).
 */
enum class WindowLayout(
    /** Minimum width of a poster card in grids. */
    val cardMinWidth: Dp,
    /** Outer content padding. */
    val gutter: Dp,
) {
    /** Phones: bottom navigation, one column of content. */
    COMPACT(112.dp, 16.dp),
    /** Small tablets, foldables, landscape phones: navigation rail. */
    MEDIUM(136.dp, 24.dp),
    /** Large tablets, desktops, Chromebooks: permanent drawer. */
    EXPANDED(160.dp, 24.dp),
    /** Android TV / Google TV: side drawer, focus navigation, overscan-safe padding. */
    TV(196.dp, 48.dp);

    val isTv: Boolean get() = this == TV

    companion object {
        /** Pure decision (tested): Material breakpoints 600 / 840 dp. */
        fun of(widthDp: Int, isTelevision: Boolean): WindowLayout = when {
            isTelevision -> TV
            widthDp < 600 -> COMPACT
            widthDp < 840 -> MEDIUM
            else -> EXPANDED
        }

        fun isTelevision(context: Context): Boolean {
            val ui = context.getSystemService(UiModeManager::class.java)
            return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }
    }
}

val LocalWindowLayout = compositionLocalOf { WindowLayout.COMPACT }

@Composable
fun rememberWindowLayout(): WindowLayout {
    val config = LocalConfiguration.current
    val context = LocalContext.current
    val tv = remember(context) { WindowLayout.isTelevision(context) }
    return remember(config.screenWidthDp, tv) { WindowLayout.of(config.screenWidthDp, tv) }
}
