package dev.yourstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.yourstream.ui.screens.navigation.YourStreamUi

/** The app (phones, tablets, Chromebooks, Android TV). One activity; screens are Navigation Compose destinations. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YourStreamUi(appVersion = BuildConfig.VERSION_NAME)
        }
    }
}
