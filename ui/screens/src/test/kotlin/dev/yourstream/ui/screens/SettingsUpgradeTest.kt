package dev.yourstream.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.ui.screens.settings.AppPreferences
import dev.yourstream.ui.screens.settings.ThemeMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Updating from 0.10: what the settings file says, and what it no longer may decide. */
@RunWith(RobolectricTestRunner::class)
class SettingsUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test
    fun torrentsSavedBy0_10AreTurnedOffAndTheRestIsKept() {
        // As 0.10 left it after the user changed only the theme: every field written, torrents = true.
        prefs.edit().putString("theme", "DARK").putBoolean("torrents", true).putInt("seek_seconds", 30).commit()
        val settings = AppPreferences(context).settings.value
        assertFalse(settings.torrentStreaming, "not chosen with the consent dialog: off")
        assertEquals(ThemeMode.DARK, settings.theme, "other choices stay")
        assertEquals(30, settings.seekSeconds)
    }

    @Test
    fun torrentsTurnedOnAfterTheUpdateStayOn() {
        AppPreferences(context).update { it.copy(torrentStreaming = true) }
        assertTrue(AppPreferences(context).settings.value.torrentStreaming, "the upgrade runs once")
    }

    @Test
    fun aFreshInstallHasTorrentsOff() {
        assertFalse(AppPreferences(context).settings.value.torrentStreaming)
    }
}
