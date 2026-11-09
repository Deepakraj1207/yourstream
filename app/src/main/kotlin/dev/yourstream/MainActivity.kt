package dev.yourstream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.yourstream.debug.DebugActivity
import dev.yourstream.ui.screens.navigation.YourStreamUi

/**
 * The app (phones, tablets, Chromebooks, Android TV). One activity; screens are Navigation
 * Compose destinations. The Stage-1 debug screens stay reachable from Settings → Developer tools.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YourStreamUi(onDeveloperTools = { startActivity(Intent(this, DebugActivity::class.java)) }, appVersion = BuildConfig.VERSION_NAME)
        }
    }
}
