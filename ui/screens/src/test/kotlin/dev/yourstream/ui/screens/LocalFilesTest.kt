package dev.yourstream.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceIds
import dev.yourstream.media.api.LocalSourceKind
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.settings.LocalFilesAction
import dev.yourstream.ui.screens.settings.LocalFilesScreen
import dev.yourstream.ui.screens.settings.LocalFilesUiState
import dev.yourstream.ui.screens.settings.LocalSourceRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Settings → Content → Files on this device. */
@RunWith(RobolectricTestRunner::class)
class LocalFilesTest {
    @get:Rule val compose = createComposeRule()

    private val movies = LocalSource(LocalSourceIds.of(0), LocalSourceKind.FOLDER, "Movies", "content://tree/movies")
    private val device = LocalSource(LocalSourceIds.of(1), LocalSourceKind.DEVICE_VIDEOS, "All videos on this device", null)

    @Test
    fun listsSourcesWithWhatTheyHoldAndRemovesThem() {
        val actions = mutableListOf<LocalFilesAction>()
        var folders = 0
        val state = LocalFilesUiState(listOf(
            LocalSourceRow(movies, files = 42, state = IndexState.COMPLETED, lastScanAt = System.currentTimeMillis() - 60_000),
            LocalSourceRow(device, state = IndexState.FAILED, error = "YourStream may no longer read the videos on this device. Allow it in Android's app settings."),
        ))
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { LocalFilesScreen(state, {}, { folders++ }, {}, { actions += it }) } }
        compose.onNodeWithText("42 videos", substring = true).assertExists()
        compose.onNodeWithText("may no longer read the videos", substring = true).assertExists()
        // "All videos" is already a source: not offered twice.
        compose.onNodeWithTag("local-add-device").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove Movies").performClick()
        compose.onNodeWithTag("local-add-folder").performClick()
        compose.onNodeWithTag("local-rescan").performClick()
        assertEquals(listOf(LocalFilesAction.Remove(movies.id), LocalFilesAction.Rescan), actions)
        assertEquals(1, folders)
    }

    @Test
    fun emptyAndSignedOutSaySo() {
        var device = 0
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) { LocalFilesScreen(LocalFilesUiState(emptyList(), signedIn = false), {}, {}, { device++ }, {}) }
        }
        compose.onNodeWithTag("local-none").assertExists()
        compose.onNodeWithTag("local-signed-out").assertExists()
        compose.onNodeWithTag("local-rescan").assertDoesNotExist()
        compose.onNodeWithText("Asks for permission to read your videos", substring = true).assertExists()
        compose.onNodeWithTag("local-add-device").performClick()
        assertEquals(1, device)
    }
}
