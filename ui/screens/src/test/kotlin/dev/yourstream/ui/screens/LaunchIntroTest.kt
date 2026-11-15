package dev.yourstream.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.yourstream.ui.screens.navigation.LaunchIntro
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The opening logo: it ends by itself in about a second, and a tap skips it. */
@RunWith(RobolectricTestRunner::class)
class LaunchIntroTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun endsByItselfInAboutASecond() {
        var done = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { LaunchIntro(ColorPainter(Color.Red), onDone = { done++ }) }
        compose.onNodeWithTag("launch-intro").assertExists()
        compose.mainClock.advanceTimeBy(400)
        assertEquals(0, done, "still showing")
        compose.mainClock.advanceTimeBy(1_600)
        compose.waitForIdle()
        assertEquals(1, done)
    }

    @Test
    fun aTapSkipsIt() {
        var done = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { LaunchIntro(ColorPainter(Color.Red), onDone = { done++ }) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("launch-intro").performClick()
        compose.onNodeWithTag("launch-intro").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(1, done, "once, even after two taps")
    }
}
