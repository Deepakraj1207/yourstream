package dev.yourstream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.LibraryFile
import dev.yourstream.catalog.api.LibraryPresence
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.addon.CatalogTitleScreen
import dev.yourstream.ui.screens.addon.CatalogTitleUiState
import dev.yourstream.ui.screens.addon.StreamFilter
import dev.yourstream.ui.screens.addon.StreamFilterContent
import dev.yourstream.ui.screens.addon.StreamGroup
import dev.yourstream.ui.screens.addon.StreamQuality
import dev.yourstream.ui.screens.addon.StreamSort
import dev.yourstream.ui.screens.navigation.OpenTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** One poster per title: the user's Telegram files as the first stream source, and Sort & filter. */
@RunWith(RobolectricTestRunner::class)
class StreamSourcesTest {
    @get:Rule val compose = createComposeRule()

    private fun web(name: String, size: Long? = null, description: String? = null) =
        AddonStream("Alpha", name, description ?: "$name file", "https://example.com/$name.mkv", null, null, sizeBytes = size)
    private fun torrent(name: String) = AddonStream("Beta", name, "$name file", null, null, "aabbccddeeff00112233445566778899aabbccdd")
    private fun telegram(res: Int, size: Long, languages: List<String> = emptyList()) =
        AddonStream("Telegram", "${res}p", "Film.$res.mkv", null, null, null, sizeBytes = size,
            library = LibraryFile(itemId = 7, episodeId = null, mediaId = res.toLong(), resolution = res), languages = languages)

    private val tg = StreamGroup("Telegram", listOf(telegram(720, 900), telegram(2160, 9_000, listOf("ta"))), id = StreamGroup.TELEGRAM)
    private val alpha = StreamGroup("Alpha", listOf(web("720p", 1_000), web("1080p", 2_000), web("4K HDR", 5_000)), id = "a")
    private val beta = StreamGroup("Beta", listOf(torrent("1080p Tamil")), id = "b")

    private fun title(streams: List<StreamGroup>) = CatalogTitleUiState("Example Film", 2024, null, loadingMeta = false, streams = streams)

    @Test
    fun telegramComesFirstEvenWhenItAnswersLast() {
        assertEquals(listOf(StreamGroup.TELEGRAM, "a", "b"), StreamGroup.ordered(listOf(alpha, beta, tg)).map { it.id })
    }

    @Test
    fun sortsInsideEachSource() {
        val best = StreamFilter().apply(listOf(alpha))
        assertEquals(listOf("4K HDR", "1080p", "720p"), best.single().streams.map { it.name })
        assertEquals(listOf("720p", "1080p", "4K HDR"), StreamFilter(sort = StreamSort.SMALLEST).apply(listOf(alpha)).single().streams.map { it.name })
        assertEquals(listOf("4K HDR", "1080p", "720p"), StreamFilter(sort = StreamSort.LARGEST).apply(listOf(alpha)).single().streams.map { it.name })
        assertEquals(alpha.streams, StreamFilter(sort = StreamSort.SOURCE).apply(listOf(alpha)).single().streams, "source order: as the add-on sent them")
        // The user's files know their resolution even when the name does not say it.
        assertEquals(listOf(2160, 720), StreamFilter().apply(listOf(tg)).single().streams.map { it.library?.resolution })
    }

    /**
     * Reported from a device: the app closed while streams loaded. Streams with and without a
     * size sorted together compared an Int (0 for "no size") with a Long and threw.
     */
    @Test
    fun streamsWithAndWithoutSizesSortTogether() {
        val mixed = StreamGroup("Alpha", listOf(web("1080p", 2_000), web("1080p", null), web("720p", 500), web("720p", null), torrent("1080p")), id = "a")
        StreamSort.entries.forEach { sort ->
            assertEquals(5, StreamFilter(sort = sort).apply(listOf(mixed)).single().streams.size, "sort $sort")
        }
        assertEquals(listOf(2_000L, null, null), StreamFilter().apply(listOf(mixed)).single().streams.take(3).map { it.sizeBytes })
        assertEquals(listOf(2_000L, 500L), StreamFilter(sort = StreamSort.LARGEST).apply(listOf(mixed)).single().streams.take(2).map { it.sizeBytes })
        assertEquals(listOf(500L, 2_000L), StreamFilter(sort = StreamSort.SMALLEST).apply(listOf(mixed)).single().streams.take(2).map { it.sizeBytes })
    }

    @Test
    fun filtersDropStreamsAndEmptiedSources() {
        val groups = listOf(tg, alpha, beta)
        // Quality: only 4K (the Telegram 2160p file and "4K HDR").
        val uhd = StreamFilter(qualities = setOf(StreamQuality.UHD)).apply(groups)
        assertEquals(listOf(StreamGroup.TELEGRAM, "a"), uhd.map { it.id })
        assertEquals(listOf("4K HDR"), uhd[1].streams.map { it.name })
        // Source and type.
        assertEquals(listOf("a", "b"), StreamFilter(telegram = false).apply(groups).map { it.id })
        assertEquals(listOf(StreamGroup.TELEGRAM), StreamFilter(addons = false).apply(groups).map { it.id })
        assertEquals(listOf(StreamGroup.TELEGRAM, "a"), StreamFilter(torrents = false).apply(groups).map { it.id })
        assertEquals(listOf(StreamGroup.TELEGRAM, "b"), StreamFilter(direct = false).apply(groups).map { it.id }, "the user's files are not 'direct' links")
        // Audio language: listed by the file, or named in the stream's text.
        assertEquals(listOf(StreamGroup.TELEGRAM, "b"), StreamFilter(languages = setOf("ta")).apply(groups) { if (it == "ta") "Tamil" else it }.map { it.id })
        // Still-loading sources stay, so their chip keeps its spinner.
        val loading = StreamGroup("Slow", emptyList(), id = "s", loading = true)
        assertEquals(listOf("s"), StreamFilter(qualities = setOf(StreamQuality.SD)).apply(listOf(alpha, loading)).map { it.id })
        assertEquals(2, StreamFilter(qualities = setOf(StreamQuality.HD), torrents = false).active)
        assertEquals(StreamQuality.FHD, StreamFilter.qualityOf(web("WEB-DL 1080p x265")))
        assertNull(StreamFilter.qualityOf(web("Unknown rip")))
    }

    @Test
    fun telegramFilesListedFirstAndTappedToPlay() {
        val tapped = mutableListOf<AddonStream>()
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) { CatalogTitleScreen(title(StreamGroup.ordered(listOf(alpha, tg))), {}, {}, {}, onStream = { tapped += it }) }
        }
        compose.onNodeWithTag("stream-addon:${StreamGroup.TELEGRAM}").assertTextContains("Telegram · 2")
        compose.onNodeWithTag("stream-group:${StreamGroup.TELEGRAM}").assertExists()
        // The first row is the best Telegram file (4K), and it is playable though it has no URL.
        compose.onAllNodesWithTag("stream")[0].performClick()
        assertEquals(listOf(2160), tapped.map { it.library?.resolution })
    }

    @Test
    fun sortFilterButtonCountsLimitsAndClearsWhenNothingIsLeft() {
        var filter by mutableStateOf(StreamFilter())
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                CatalogTitleScreen(title(listOf(tg, alpha, beta)), {}, {}, {}, filter = filter, onFilter = { filter = it }, onStream = {})
            }
        }
        compose.onNodeWithTag("streams-sort-filter").assertTextContains("Sort & filter")
        compose.onNodeWithTag("stream-addon:all").assertTextContains("All · 6")

        // Torrents off: the chips count what is left.
        filter = StreamFilter(torrents = false)
        compose.onNodeWithTag("streams-sort-filter").assertTextContains("Filters · 1")
        compose.onNodeWithTag("stream-addon:all").assertTextContains("All · 5")
        compose.onNodeWithTag("stream-addon:b").assertDoesNotExist()

        // Nothing matches: say how many are hidden, and clear (keeping the sort).
        filter = StreamFilter(sort = StreamSort.LARGEST, telegram = false, addons = false)
        compose.onNodeWithTag("streams-filtered-out").assertExists()
        compose.onNodeWithText("6 streams are hidden by your filters.").assertExists()
        compose.onNodeWithTag("streams-clear-filters").performClick()
        assertEquals(StreamFilter(sort = StreamSort.LARGEST), filter)
    }

    @Test
    fun sheetEditsACopyAndSaysHowManyWillShow() {
        val applied = mutableListOf<StreamFilter>()
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { StreamFilterContent(StreamFilter(), listOf(tg, alpha, beta)) { applied += it } } }
        compose.onNodeWithTag("stream-filter-apply").assertTextContains("Show 6 streams")
        compose.onNodeWithTag("stream-filter:sort:BEST").assertIsSelected()
        compose.onNodeWithTag("stream-filter:quality:UHD").performClick()
        compose.onNodeWithTag("stream-filter-apply").assertTextContains("Show 2 streams")
        compose.onNodeWithTag("stream-filter:sort:SMALLEST").performClick()
        // Tamil was found in the streams (listed by a file, named by a torrent).
        compose.onNodeWithTag("stream-filter-sheet").performScrollToNode(hasTestTag("stream-filter:language:ta"))
        compose.onNodeWithTag("stream-filter:language:ta").assertExists()
        compose.onNodeWithTag("stream-filter-sheet").performScrollToNode(hasTestTag("stream-filter-apply"))
        compose.onNodeWithTag("stream-filter-apply").performClick()
        assertEquals(listOf(StreamFilter(sort = StreamSort.SMALLEST, qualities = setOf(StreamQuality.UHD))), applied)
    }

    @Test
    fun onePosterPerTitle() {
        // A catalog poster the user also has on Telegram: badged, and it opens the title page.
        val item = CatalogItem(CatalogItemRef(CatalogProviderId("cinemeta"), CatalogType.MOVIE, "tt0111161", imdbId = "tt0111161"), "Example Film", 1994)
        val had = Presentation.tile(item, LibraryPresence(7, "Example Film", 2))
        assertEquals(Presentation.TELEGRAM_BADGE, had.card.badge)
        assertIs<OpenTarget.CatalogTitle>(had.open)
        assertEquals(7L, had.itemId)
        assertNull(Presentation.tile(item, null).card.badge)

        // A matched Telegram movie opens that same Cinemeta page; an unmatched one keeps its own.
        val matched = Presentation.tile(summary(7).copy(imdbId = "tt0111161", metadataTitle = "Example Film"))
        val route = assertIs<OpenTarget.CatalogTitle>(matched.open).route
        assertEquals("cinemeta" to "tt0111161", route.provider to route.id)
        assertIs<OpenTarget.Details>(Presentation.tile(summary(8)).open)
        assertIs<OpenTarget.Details>(Presentation.tile(summary(9, LibraryKind.SERIES).copy(imdbId = "kitsu:1")).open)
    }

    @Test
    fun recentlyAddedIsTaggedFromTelegram() {
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { dev.yourstream.ui.core.SectionHeader("Recently added", onSeeAll = null, tag = "From Telegram") } }
        compose.onNodeWithText("FROM TELEGRAM", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("row-tag", useUnmergedTree = true).assertExists()
    }
}
