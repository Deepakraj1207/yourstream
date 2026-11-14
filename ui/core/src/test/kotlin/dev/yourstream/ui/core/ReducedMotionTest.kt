package dev.yourstream.ui.core

import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The system "Remove animations" option is read, and followed when it changes. */
@RunWith(RobolectricTestRunner::class)
class ReducedMotionTest {
    @get:Rule val compose = createComposeRule()
    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @Test
    fun followsTheSystemAnimationScale() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        compose.setContent { Text("reduced=${rememberSystemReducedMotion()}") }
        compose.onNodeWithText("reduced=false").assertExists()
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.onNodeWithText("reduced=true").assertExists()
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        compose.onNodeWithText("reduced=false").assertExists()
    }
}
