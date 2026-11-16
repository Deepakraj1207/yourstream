package dev.yourstream.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.LocalHapticsEnabled
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.navigation.AdaptiveShell
import dev.yourstream.ui.screens.navigation.TopLevel
import dev.yourstream.ui.screens.settings.AppSettings
import dev.yourstream.ui.screens.settings.LocalAppSettings
import dev.yourstream.ui.screens.settings.SettingsHost
import dev.yourstream.ui.screens.settings.SettingsPage
import dev.yourstream.ui.screens.settings.SettingsPageScreen
import dev.yourstream.ui.screens.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Haptic feedback: which touches are felt, how, and that the setting turns it off. */
@RunWith(RobolectricTestRunner::class)
class HapticsTest {
    @get:Rule val compose = createComposeRule()

    /** Records what the app asked the phone to play. */
    private class Recorder : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { played += hapticFeedbackType }
    }

    @Test
    fun tabsChipsAndSwitchesAreFelt() {
        val rec = Recorder()
        var enabled by mutableStateOf(true)
        var tab by mutableStateOf(TopLevel.HOME)
        var chip by mutableStateOf(1)
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides rec, LocalHapticsEnabled provides enabled, LocalAppSettings provides AppSettings()) {
                YourStreamTheme(WindowLayout.COMPACT) {
                    AdaptiveShell(WindowLayout.COMPACT, tab, { tab = it }) {
                        ChipRow(listOf(ChipOption(1, "One"), ChipOption(2, "Two")), chip, { chip = it })
                    }
                }
            }
        }
        // Another tab: a tick; the same tab again: nothing.
        compose.onNodeWithTag("nav:SEARCH").performClick()
        compose.onNodeWithTag("nav:SEARCH").performClick()
        assertEquals(listOf(HapticFeedbackType.SegmentTick), rec.played)
        // Another chip: a tick.
        compose.onNodeWithTag("chip:Two").performClick()
        assertEquals(2, rec.played.size)
        // Settings → Accessibility → Haptic feedback off: nothing is played.
        enabled = false
        compose.onNodeWithTag("nav:LIBRARY").performClick()
        compose.onNodeWithTag("chip:One").performClick()
        assertEquals(2, rec.played.size)
    }

    @Test
    fun switchesFeelOnAndOffAndTheSettingIsSaved() {
        val rec = Recorder()
        var prefs by mutableStateOf(AppSettings())
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides rec) {
                YourStreamTheme(WindowLayout.COMPACT) {
                    SettingsPageScreen(SettingsPage.APPEARANCE, SettingsHost(SettingsUiState("Asha", true, 3, ""), "1.2.0", prefs = prefs, onPrefs = { prefs = it }), {})
                }
            }
        }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-reduce-motion"))
        compose.onNodeWithTag("pref-reduce-motion").performClick()
        assertEquals(listOf(HapticFeedbackType.ToggleOn), rec.played)
        compose.onNodeWithTag("pref-reduce-motion").performClick()
        assertEquals(HapticFeedbackType.ToggleOff, rec.played.last())
        // The switch itself, beside Reduce motion; on by default, kept in the settings.
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-haptics"))
        assertEquals(true, AppSettings().haptics)
        compose.onNodeWithTag("pref-haptics").performClick()
        assertEquals(false, prefs.haptics)
    }
}
