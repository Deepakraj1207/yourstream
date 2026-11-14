package dev.yourstream.ui.core

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * True when motion should be kept to a minimum: the system's "Remove animations" accessibility
 * option (animator duration scale 0) or the app's own "Reduce motion" setting.
 *
 * Compose already shortens finite animations by the system scale. This flag covers what that does
 * not: looping animations (skeleton pulse, indexing spinner), sliding and zooming page
 * transitions, and the app setting for people who want less motion without turning it off
 * system-wide.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** The system "Remove animations" option, kept up to date while the screen is shown. */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.applicationContext.contentResolver
    fun read() = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
