package dev.yourstream.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.settings.DeviceStorage
import dev.yourstream.ui.screens.settings.StorageArea
import dev.yourstream.ui.screens.settings.StorageMessage
import dev.yourstream.ui.screens.settings.StorageScreen
import dev.yourstream.ui.screens.settings.StorageUiState
import dev.yourstream.ui.screens.settings.StorageUsage
import dev.yourstream.ui.screens.settings.StorageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Settings → Storage: sizes, clearing, the Telegram confirmation, and refusals. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StorageTest {
    @get:Rule val compose = createComposeRule()

    private val mb = 1_000_000L
    private val usage = StorageUsage(
        mapOf(StorageArea.TELEGRAM to 1_500 * mb, StorageArea.WEB_STREAMS to 200 * mb, StorageArea.TORRENTS to 0, StorageArea.IMAGES to 40 * mb),
        freeBytes = 8_000 * mb,
    )

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun showsEachCacheAndAsksBeforeClearingTelegramVideos() {
        val cleared = mutableListOf<StorageArea>()
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { StorageScreen(StorageUiState(usage), { cleared += it }, {}) } }
        compose.onNodeWithText("YourStream uses 1.7 GB for caches", substring = true).assertExists()
        compose.onNodeWithText("8.0 GB free on this device", substring = true).assertExists()
        // Nothing to clear: no button to press.
        compose.onNodeWithTag("storage-clear:TORRENTS").assertIsNotEnabled()

        compose.onNodeWithTag("storage-clear:IMAGES").performClick()
        assertEquals(listOf(StorageArea.IMAGES), cleared)

        // Telegram's cache holds saved downloads too: confirm first.
        compose.onNodeWithTag("storage-clear:TELEGRAM").performClick()
        assertEquals(listOf(StorageArea.IMAGES), cleared)
        compose.onNodeWithText("This also removes videos you saved", substring = true).assertExists()
        compose.onNodeWithTag("storage-confirm").performClick()
        assertEquals(listOf(StorageArea.IMAGES, StorageArea.TELEGRAM), cleared)
    }

    @Test
    fun clearingReportsTheResultAndMeasuresAgain() = runTest {
        val storage = object : DeviceStorage {
            var current = usage
            var refuse = false
            override suspend fun usage() = current
            override suspend fun clear(area: StorageArea): AppResult<Unit> {
                if (refuse) return AppResult.Err(AppError.InvalidState("a video is playing. Stop it and try again."))
                current = current.copy(bytes = current.bytes + (area to 0L))
                return AppResult.Ok(Unit)
            }
        }
        val vm = StorageViewModel(storage)
        assertEquals(usage, vm.state.value.usage)

        vm.clear(StorageArea.WEB_STREAMS)
        assertEquals(StorageMessage.Cleared(StorageArea.WEB_STREAMS), vm.state.value.message)
        assertEquals(0L, vm.state.value.usage!!.bytes[StorageArea.WEB_STREAMS])
        assertEquals(null, vm.state.value.clearing)

        storage.refuse = true
        vm.clear(StorageArea.IMAGES)
        val failed = assertIs<StorageMessage.Failed>(vm.state.value.message)
        assertEquals("a video is playing. Stop it and try again.", failed.reason)
        assertEquals(40 * mb, vm.state.value.usage!!.bytes[StorageArea.IMAGES], "nothing removed")
    }
}
