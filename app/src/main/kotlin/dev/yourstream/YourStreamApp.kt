package dev.yourstream

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.yourstream.di.ApplicationScope
import dev.yourstream.telegram.tdlib.TelegramInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YourStreamApp : Application() {

    @Inject lateinit var telegram: TelegramInitializer
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Starts TDLib; a saved session resumes without a new login.
        appScope.launch { telegram.start() }
    }
}
