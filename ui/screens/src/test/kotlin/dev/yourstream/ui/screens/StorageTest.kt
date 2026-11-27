package dev.yourstream.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

        // Below "Clear cache" on a phone: scrolled to, as a user would.
        compose.onNodeWithTag("storage-clear:IMAGES").performScrollTo().performClick()
        assertEquals(listOf(StorageArea.IMAGES), cleared)

        // Telegram's cache holds saved downloads too: confirm first.
        compose.onNodeWithTag("storage-clear:TELEGRAM").performScrollTo().performClick()
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

    /** A storage whose caches empty when cleared, with or without saved videos, refusing while "playing". */
    private class FakeStorage(var current: StorageUsage, val saved: Boolean = false, var playing: Boolean = false) : DeviceStorage {
        val cleared = mutableListOf<StorageArea>()
        override suspend fun usage() = current
        override suspend fun hasSavedDownloads() = saved
        override suspend fun clear(area: StorageArea): AppResult<Unit> {
            if (playing) return AppResult.Err(AppError.InvalidState("a video is playing. Stop it and try again."))
            cleared += area
            current = current.copy(bytes = current.bytes + (area to 0L))
            return AppResult.Ok(Unit)
        }
    }

    @Test
    fun clearCacheAsksFirstAndSaysSavedVideosAreKept() {
        var all = 0
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) { StorageScreen(StorageUiState(usage, savedDownloads = true), {}, {}, onClearAll = { all++ }) }
        }
        compose.onNodeWithTag("storage-clear-all").performClick()
        assertEquals(0, all, "asks first")
        compose.onNodeWithText("Your library, watch history, settings and sign-in are kept", substring = true).assertExists()
        compose.onNodeWithText("You saved videos to this device, so Telegram videos are kept", substring = true).assertExists()
        compose.onNodeWithTag("storage-confirm-all").performClick()
        assertEquals(1, all)
    }

    @Test
    fun clearCacheEmptiesEveryCache() = runTest {
        val storage = FakeStorage(usage)
        val vm = StorageViewModel(storage)
        vm.clearAll()
        // Torrents held nothing: not asked.
        assertEquals(listOf(StorageArea.TELEGRAM, StorageArea.WEB_STREAMS, StorageArea.IMAGES), storage.cleared)
        val m = assertIs<StorageMessage.ClearedAll>(vm.state.value.message)
        assertEquals(1_740 * mb, m.freedBytes)
        assertEquals(false, m.keptTelegram)
        assertEquals(0L, vm.state.value.usage!!.total)
        assertEquals(false, vm.state.value.clearingAll)
    }

    @Test
    fun clearCacheKeepsTelegramVideosWhenVideosAreSaved() = runTest {
        val storage = FakeStorage(usage, saved = true)
        val vm = StorageViewModel(storage)
        assertEquals(true, vm.state.value.savedDownloads)
        vm.clearAll()
        assertEquals(listOf(StorageArea.WEB_STREAMS, StorageArea.IMAGES), storage.cleared)
        val m = assertIs<StorageMessage.ClearedAll>(vm.state.value.message)
        assertEquals(true, m.keptTelegram)
        assertEquals(240 * mb, m.freedBytes)
        assertEquals(1_500 * mb, vm.state.value.usage!!.bytes[StorageArea.TELEGRAM])
    }

    @Test
    fun clearCacheStopsWhileAVideoPlays() = runTest {
        val storage = FakeStorage(usage, playing = true)
        val vm = StorageViewModel(storage)
        vm.clearAll()
        val m = assertIs<StorageMessage.ClearedAll>(vm.state.value.message)
        assertEquals(1, m.failed.size, "stops at the first refusal")
        assertEquals("a video is playing. Stop it and try again.", m.failed.single().reason)
        assertEquals(0L, m.freedBytes)
    }
}
