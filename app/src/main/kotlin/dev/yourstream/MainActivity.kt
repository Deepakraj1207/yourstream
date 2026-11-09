package dev.yourstream

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.yourstream.ui.screens.navigation.YourStreamUi
import dev.yourstream.ui.screens.player.PictureInPicture
import dev.yourstream.ui.screens.update.AppUpdates
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The app (phones, tablets, Chromebooks, Android TV). One activity; screens are Navigation Compose destinations. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var updates: AppUpdates

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YourStreamUi(appVersion = BuildConfig.VERSION_NAME)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps": a waiting update continues by itself.
        lifecycleScope.launch { runCatching { updates.onAppResumed() } }
    }

    /** Leaving the app while a video plays keeps it playing in a floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!PictureInPicture.eligible.value || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PictureInPicture.active.value = isInPictureInPictureMode
    }
}
