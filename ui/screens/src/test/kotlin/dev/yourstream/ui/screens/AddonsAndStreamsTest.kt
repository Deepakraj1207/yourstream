package dev.yourstream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.sync.CatalogAddon
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.addon.CatalogTitleAction
import dev.yourstream.ui.screens.addon.CatalogTitleScreen
import dev.yourstream.ui.screens.addon.CatalogTitleUiState
import dev.yourstream.ui.screens.addon.StreamGroup
import dev.yourstream.ui.screens.settings.AddonRow
import dev.yourstream.ui.screens.settings.AddonsAction
import dev.yourstream.ui.screens.settings.AddonsScreen
import dev.yourstream.ui.screens.settings.AddonsUiState
import dev.yourstream.ui.screens.settings.RefreshResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The Add-ons page's refresh button, and a title's streams grouped by add-on with add-on chips. */
@RunWith(RobolectricTestRunner::class)
class AddonsAndStreamsTest {
    @get:Rule val compose = createComposeRule()

    private val addon = AddonRow(CatalogAddon("custom:a.example.com", "Alpha", "https://a.example.com/manifest.json", false, true), 2, true, streams = true)

    @Test
    fun refreshButtonAsksAgainAndSaysHowManyAnswered() {
        var state by mutableStateOf(AddonsUiState(listOf(addon)))
        val actions = mutableListOf<AddonsAction>()
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { AddonsScreen(state, { actions += it }, {}) } }

        compose.onNodeWithTag("addons-refresh").assertIsEnabled().performClick()
        assertEquals(listOf<AddonsAction>(AddonsAction.Refresh), actions)

        state = state.copy(refreshing = true)
        compose.onNodeWithTag("addons-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("addons-refreshing").assertExists()

        state = state.copy(refreshing = false, refreshed = RefreshResult(1, 1))
        compose.onNodeWithTag("addons-refreshed").assertTextContains("the add-on answered", substring = true)
        state = state.copy(refreshed = RefreshResult(2, 3))
        compose.onNodeWithTag("addons-refreshed").assertTextContains("2 of 3 add-ons answered", substring = true)
    }

    private fun stream(addon: String, name: String, url: String? = "https://example.com/$name.mkv", hash: String? = null) =
        AddonStream(addon, name, "$name file", url, null, hash)

    private val alpha = StreamGroup("Alpha", listOf(stream("Alpha", "1080p"), stream("Alpha", "720p")), id = "a")
    private val beta = StreamGroup("Beta", listOf(stream("Beta", "4K", url = null, hash = "aabbccddeeff00112233445566778899aabbccdd")), id = "b")

    private fun title(streams: List<StreamGroup>, loading: Boolean = false, chosen: String? = null) =
        CatalogTitleUiState("Example Film", 2024, null, loadingMeta = false, streams = streams, loadingStreams = loading, streamAddon = chosen)

    @Test
    fun streamsAreGroupedByAddonWithChipsToShowOneAddon() {
        var state by mutableStateOf(title(listOf(alpha, beta, StreamGroup("Gamma", emptyList(), id = "c"), StreamGroup("Delta", emptyList(), "timeout", id = "d"))))
        val actions = mutableListOf<CatalogTitleAction>()
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { CatalogTitleScreen(state, { actions += it }, {}, {}, onStream = {}) } }

        // All: every add-on's streams under its name, with counts.
        compose.onNodeWithTag("stream-addon:all").assertIsSelected().assertTextContains("All · 3")
        compose.onNodeWithTag("stream-addon:a").assertTextContains("Alpha · 2")
        compose.onNodeWithTag("stream-group:a").assertTextContains("2 streams", substring = true)
        compose.onNodeWithTag("stream-group:b").assertTextContains("1 stream", substring = true)
        // Add-ons that found nothing or did not answer are named, not hidden; they get no chip.
        compose.onNodeWithText("No streams from Gamma.").assertExists()
        compose.onNodeWithText("Did not answer: Delta. Refresh to ask again.").assertExists()
        compose.onNodeWithTag("stream-addon:c").assertDoesNotExist()

        compose.onNodeWithTag("stream-addon:b").performClick()
        compose.onNodeWithTag("streams-refresh").performClick()
        assertEquals(listOf(CatalogTitleAction.SelectStreamAddon("b"), CatalogTitleAction.RefreshStreams), actions)

        // One add-on chosen: only its streams.
        state = state.copy(streamAddon = "b")
        compose.onNodeWithTag("stream-addon:b").assertIsSelected()
        compose.onNodeWithTag("stream-group:b").assertExists()
        compose.onNodeWithTag("stream-group:a").assertDoesNotExist()
        compose.onNodeWithTag("streams-missing").assertDoesNotExist()
    }

    @Test
    fun addonsAppearAsTheyAnswer() {
        var state by mutableStateOf(title(listOf(StreamGroup("Alpha", emptyList(), id = "a", loading = true), StreamGroup("Beta", emptyList(), id = "b", loading = true)), loading = true))
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { CatalogTitleScreen(state, {}, {}, {}, onStream = {}) } }
        // Still asking: a chip per add-on, the refresh waits.
        compose.onNodeWithTag("stream-addon:a").assertExists()
        compose.onNodeWithTag("streams-refresh").assertIsNotEnabled()
        // Alpha answered first: its streams show while Beta is still asked.
        state = state.copy(streams = listOf(alpha, StreamGroup("Beta", emptyList(), id = "b", loading = true)))
        compose.onNodeWithTag("stream-group:a").assertExists()
        compose.onNodeWithTag("stream-addon:a").assertTextContains("Alpha · 2")
        // All answered, nothing found anywhere.
        state = title(listOf(StreamGroup("Alpha", emptyList(), id = "a"), StreamGroup("Beta", emptyList(), id = "b")))
        compose.onNodeWithText("No streams found for this title.").assertExists()
        compose.onNodeWithTag("streams-refresh").assertIsEnabled()
    }

    @Test
    fun groupOrderStaysStableWhileAnswersArrive() {
        val torrentOnly = beta
        val loading = StreamGroup("Slow", emptyList(), id = "s", loading = true)
        val empty = StreamGroup("Empty", emptyList(), id = "e")
        val failed = StreamGroup("Down", emptyList(), "timeout", id = "f")
        // Groups of equal rank keep the order they came in (sortedBy is stable): Empty before Down.
        assertEquals(listOf("a", "b", "s", "e", "f"), StreamGroup.ordered(listOf(empty, loading, torrentOnly, failed, alpha)).map { it.id })
        assertEquals(listOf("a", "b"), title(listOf(alpha, beta, empty)).streamTabs.map { it.id })
        assertEquals(listOf("a", "b"), title(listOf(alpha, beta), chosen = "gone").shownStreams.map { it.id }, "a chosen add-on that left: back to all")
    }
}
