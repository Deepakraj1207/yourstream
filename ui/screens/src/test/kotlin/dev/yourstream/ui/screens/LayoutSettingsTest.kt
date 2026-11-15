package dev.yourstream.ui.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.CatalogVideo
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.addon.CatalogTitleScreen
import dev.yourstream.ui.screens.addon.CatalogTitleUiState
import dev.yourstream.ui.screens.addon.StreamGroup
import dev.yourstream.ui.screens.home.CatalogRow
import dev.yourstream.ui.screens.home.HomeBanner
import dev.yourstream.ui.screens.home.HomeCatalogLayout
import dev.yourstream.ui.screens.home.HomeRows
import dev.yourstream.ui.screens.home.HomeScreen
import dev.yourstream.ui.screens.home.HomeUiState
import dev.yourstream.ui.screens.navigation.MainNavigationBar
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.navigation.TopLevel
import dev.yourstream.ui.screens.settings.AppPreferences
import dev.yourstream.ui.screens.settings.AppSettings
import dev.yourstream.ui.screens.settings.ContinueOpens
import dev.yourstream.ui.screens.settings.ContinueStyle
import dev.yourstream.ui.screens.settings.EpisodeCards
import dev.yourstream.ui.screens.settings.LayoutSettings
import dev.yourstream.ui.screens.settings.LocalAppSettings
import dev.yourstream.ui.screens.settings.NavStyle
import dev.yourstream.ui.screens.settings.SettingsHost
import dev.yourstream.ui.screens.settings.SettingsPage
import dev.yourstream.ui.screens.settings.SettingsPageScreen
import dev.yourstream.ui.screens.settings.SettingsUiState
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.yourstream.library.api.PlaybackTarget
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings → Layout in Nuvio's structure, and what each option changes on the screens. */
@RunWith(RobolectricTestRunner::class)
class LayoutSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings(page: SettingsPage, prefs: () -> AppSettings, onPrefs: (AppSettings) -> Unit, onPage: (SettingsPage) -> Unit = {}) =
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                SettingsPageScreen(page, SettingsHost(SettingsUiState("Asha", true, 3, ""), prefs = prefs(), onPrefs = onPrefs, onPage = onPage), {})
            }
        }

    @Test
    fun theLayoutHubOpensItsPagesInNuviosOrder() {
        val opened = mutableListOf<SettingsPage>()
        settings(SettingsPage.LAYOUT, { AppSettings() }, {}) { opened += it }
        for (p in listOf(SettingsPage.LAYOUT_HOME, SettingsPage.LAYOUT_CONTINUE, SettingsPage.LAYOUT_POSTERS, SettingsPage.LAYOUT_STREAMS,
            SettingsPage.LAYOUT_DETAIL, SettingsPage.LAYOUT_NAVIGATION)) {
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-page:${p.name}"))
            compose.onNodeWithTag("settings-page:${p.name}").performClick()
        }
        assertEquals(6, opened.size)
        // The navigation row says what it is set to, like Nuvio's "Adaptive · Glow off".
        compose.onNodeWithTag("settings-page:LAYOUT_NAVIGATION").assertTextContains("Adaptive", substring = true)
    }

    @Test
    fun optionsChangeTheSettings() {
        var prefs by mutableStateOf(AppSettings())
        var page by mutableStateOf(SettingsPage.LAYOUT_POSTERS)
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                SettingsPageScreen(page, SettingsHost(SettingsUiState("Asha", true, 3, ""), prefs = prefs, onPrefs = { prefs = it }), {})
            }
        }
        compose.onNodeWithTag("poster-preview").assertExists()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-poster-width"))
        compose.onNodeWithTag("pref-poster-width:Large · 140 dp").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-poster-radius"))
        compose.onNodeWithTag("pref-poster-radius:Pill").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-hide-labels"))
        compose.onNodeWithTag("pref-hide-labels").performClick()
        assertEquals(LayoutSettings(posterWidth = 140, posterRadius = 16, hideLabels = true), prefs.layout)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-poster-reset"))
        compose.onNodeWithTag("pref-poster-reset").performClick()
        assertEquals(LayoutSettings(), prefs.layout)

        page = SettingsPage.LAYOUT_CONTINUE
        compose.onNodeWithTag("pref-continue-style:Poster").performClick()
        compose.onNodeWithTag("pref-continue-opens:Open details").performClick()
        assertEquals(ContinueStyle.POSTER, prefs.layout.continueStyle)
        assertEquals(ContinueOpens.DETAILS, prefs.layout.continueOpens)

        page = SettingsPage.LAYOUT_NAVIGATION
        compose.onNodeWithTag("pref-nav-glow").assertDoesNotExist()
        compose.onNodeWithTag("pref-nav-style:Always expanded").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-nav-glow"))
        compose.onNodeWithTag("pref-nav-glow").performClick()
        assertEquals(NavStyle.EXPANDED, prefs.layout.navStyle)
        assertEquals(true, prefs.layout.navGlow)

        page = SettingsPage.LAYOUT_DETAIL
        compose.onNodeWithTag("pref-episode-cards:Horizontal").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-show-cast"))
        compose.onNodeWithTag("pref-show-cast").performClick()
        assertEquals(EpisodeCards.HORIZONTAL, prefs.layout.episodeCards)
        assertEquals(false, prefs.layout.showCast)
    }

    @Test
    fun layoutSettingsAreKeptAndOldPosterSizesCarryOver() {
        val p = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        p.edit().clear().putString("poster_size", "LARGE").commit()
        assertEquals(140, AppPreferences(context).settings.value.layout.posterWidth, "Large before 0.11.4 = 140 dp")
        val store = AppPreferences(context)
        store.update { it.copy(layout = it.layout.copy(navStyle = NavStyle.CLASSIC, posterRadius = 0, sizeBadges = false, episodeCards = EpisodeCards.HORIZONTAL)) }
        val again = AppPreferences(context).settings.value.layout
        assertEquals(NavStyle.CLASSIC, again.navStyle)
        assertEquals(0, again.posterRadius)
        assertEquals(false, again.sizeBadges)
        assertEquals(EpisodeCards.HORIZONTAL, again.episodeCards)
    }

    @Test
    fun homeCatalogLayoutArrangesHidesAndRenames() {
        val keys = listOf("a", "b", "c", "d")
        val l = HomeCatalogLayout(hidden = setOf("b"), order = listOf("c"), names = mapOf("a" to "Mine"))
        assertEquals(listOf("c", "a", "d"), l.arrange(keys) { it })
        assertEquals("Mine", l.nameOf("a", "Popular"))
        assertEquals("Popular", l.nameOf("d", "Popular"))
        assertEquals(listOf("c", "b", "a", "d"), l.move(keys, "b", -1).order)
        assertEquals(l, l.move(keys, "c", -1), "already first")
    }

    private val done = LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))

    private fun tile(key: String, title: String, itemId: Long? = null, open: OpenTarget = OpenTarget.Details(1)) =
        Tile(MediaCardModel(key, title, "2024"), open, itemId = itemId)

    @Test
    fun homeFollowsTheLayoutSettings() {
        var layout by mutableStateOf(LayoutSettings())
        val opened = mutableListOf<OpenTarget>()
        val cw = tile("cw:1", "Leo", itemId = 7, open = OpenTarget.Play(PlaybackTarget(7)))
        val catalog = CatalogRow("cat:x:MOVIE:top", "Popular", listOf(tile("c:1", "Out now"), tile("c:2", "Next year")), CatalogType.MOVIE, unreleased = setOf("c:2"))
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides AppSettings(layout = layout)) {
                YourStreamTheme(WindowLayout.COMPACT) {
                    val none = flowOf(PagingData.from(emptyList<Tile>(), done)).collectAsLazyPagingItems()
                    HomeScreen(HomeUiState(null, UiState.Empty(dev.yourstream.ui.core.EmptyKind.LIBRARY_EMPTY), UiState.Content(listOf(cw)), libraryCount = 3,
                        catalogs = listOf(catalog)), HomeRows(none, none), {}, { opened += it }, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("row:Popular"))
        compose.onNodeWithTag("card:c:2").assertExists()
        layout = LayoutSettings(showCatalogType = true, hideUnreleased = true, continueOpens = ContinueOpens.DETAILS)
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("row:Popular · Movies"))
        compose.onNodeWithTag("card:c:2").assertDoesNotExist()
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("card:cw:1"))
        compose.onNodeWithTag("card:cw:1").performClick()
        assertEquals(OpenTarget.Details(7), opened.last(), "Continue Watching opens the details")
    }

    @Test
    fun homeShowsTheHeroCarouselWhenHeroCatalogsAreChosen() {
        var slides by mutableStateOf(emptyList<dev.yourstream.ui.screens.home.HeroSlide>())
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                val none = flowOf(PagingData.from(emptyList<Tile>(), done)).collectAsLazyPagingItems()
                val hero = dev.yourstream.ui.screens.home.HeroItem(dev.yourstream.ui.screens.home.HeroItem.Kind.NEW, "hero:1", "Leo", "2023", null, null,
                    OpenTarget.Play(PlaybackTarget(1)), OpenTarget.Details(1))
                HomeScreen(HomeUiState(null, UiState.Content(hero), UiState.Empty(dev.yourstream.ui.core.EmptyKind.NOTHING_IN_PROGRESS), libraryCount = 3,
                    heroSlides = slides), HomeRows(none, none), {}, {}, {}, {}, {})
            }
        }
        // No hero catalogs: the library hero.
        compose.onNodeWithTag("hero").assertExists()
        compose.onNodeWithTag("hero-carousel").assertDoesNotExist()
        slides = listOf(dev.yourstream.ui.screens.home.HeroSlide("hero:x", "Immortal", null, null, CatalogType.MOVIE, null, null, 2026, null, emptyList(), OpenTarget.Details(9)))
        compose.onNodeWithTag("hero-carousel").assertExists()
        compose.onNodeWithTag("hero").assertDoesNotExist()
    }

    @Test
    fun streamsAndTitlePageFollowTheLayoutSettings() {
        var layout by mutableStateOf(LayoutSettings())
        val ref = CatalogItemRef(CatalogProviderId("x"), CatalogType.SERIES, "tt1")
        val videos = (1..3).map { CatalogVideo("tt1:1:$it", 1, it, "Episode $it", null) }
        val stream = AddonStream("A", "1080p", "Film.mkv", "https://example.com/a.mkv", null, null, sizeBytes = 1_500_000_000L)
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides AppSettings(layout = layout)) {
                YourStreamTheme(WindowLayout.COMPACT) {
                    CatalogTitleScreen(CatalogTitleUiState("Show", 2020, null, series = true,
                        meta = CatalogMetadata(ref, "Show", 2020, "A long description.", cast = listOf("Someone"), videos = videos),
                        loadingMeta = false, seasons = listOf(1), season = 1, episode = videos[0],
                        streams = listOf(StreamGroup("A", listOf(stream), id = "a", logo = "https://example.com/logo.png"))), {}, {}, {}, onStream = {})
                }
            }
        }
        compose.onNodeWithText("A long description.").assertExists()
        compose.onNodeWithTag("catalog-title").performScrollToNode(hasTestTag("stream"))
        // Inside the tappable stream row (one merged node for screen readers): the unmerged tree.
        compose.onNode(hasTestTag("stream-size") and androidx.compose.ui.test.hasAnyDescendant(hasText("1.4 GB")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stream-logo:a", useUnmergedTree = true).assertExists()
        layout = LayoutSettings(sizeBadges = false, addonLogo = false, showOverview = false, episodeCards = EpisodeCards.HORIZONTAL)
        compose.onNodeWithTag("stream-size", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("stream-logo:a", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("catalog-title").performScrollToNode(hasTestTag("episode-cards"))
        compose.onNodeWithTag("episode-cards").assertExists()
        compose.onNodeWithText("A long description.").assertDoesNotExist()
        assertEquals("1.4 GB", dev.yourstream.ui.screens.addon.sizeLabel(1_500_000_000L))
        assertEquals("700 MB", dev.yourstream.ui.screens.addon.sizeLabel(734_003_200L))
    }

    @Test
    fun adaptiveBarShrinksWhileScrollingDownAndGrowsBack() {
        // Classic look, Adaptive (the default): Nuvio's floating bar over the content.
        val settings = AppSettings(style = dev.yourstream.ui.screens.settings.UiStyle.CLASSIC)
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides settings) {
                YourStreamTheme(WindowLayout.COMPACT) {
                    dev.yourstream.ui.screens.navigation.AdaptiveShell(WindowLayout.COMPACT, TopLevel.HOME, {}) { m ->
                        androidx.compose.foundation.lazy.LazyColumn(m.testTag("content")) {
                            items(60) { androidx.compose.material3.Text("Row $it", androidx.compose.ui.Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("nav:expanded").assertExists()
        compose.onNodeWithTag("content").performTouchInput { swipeUp() }
        compose.onNodeWithTag("nav:compact").assertExists()
        compose.onNodeWithTag("nav:HOME").assertIsSelected()
        compose.onNodeWithTag("content").performTouchInput { swipeDown() }
        compose.onNodeWithTag("nav:expanded").assertExists()
    }

    @Test
    fun floatingBarKeepsTabsAndTouchTargets() {
        var layout by mutableStateOf(LayoutSettings(navStyle = NavStyle.EXPANDED, navGlow = true))
        val selected = mutableListOf<TopLevel>()
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides AppSettings(layout = layout)) {
                YourStreamTheme(WindowLayout.COMPACT) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(320.dp)) {
                        MainNavigationBar(TopLevel.LIBRARY, { selected += it })
                    }
                }
            }
        }
        compose.onNodeWithTag("nav:glow").assertExists()
        compose.onNodeWithTag("nav:LIBRARY").assertIsSelected()
        compose.onNodeWithTag("nav:SEARCH").performClick()
        assertEquals(listOf(TopLevel.SEARCH), selected)
        val small = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().filter { n ->
            with(compose.density) { n.size.width.toDp().value < 47.5f || n.size.height.toDp().value < 47.5f }
        }
        assertTrue(small.isEmpty(), "touch targets under 48 dp: ${small.map { it.size }}")
        layout = layout.copy(navGlow = false)
        compose.onNodeWithTag("nav:glow").assertDoesNotExist()
    }
}
