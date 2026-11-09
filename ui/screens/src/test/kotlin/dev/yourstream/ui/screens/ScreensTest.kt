package dev.yourstream.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.ErrorKind
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.details.DetailsAction
import dev.yourstream.ui.screens.details.DetailsContent
import dev.yourstream.ui.screens.details.DetailsScreen
import dev.yourstream.ui.screens.details.DetailsUiState
import dev.yourstream.ui.screens.details.PrimaryAction
import dev.yourstream.ui.screens.home.HomeBanner
import dev.yourstream.ui.screens.home.HomeScreen
import dev.yourstream.ui.screens.home.HomeRows
import dev.yourstream.ui.screens.home.HeroItem
import dev.yourstream.ui.screens.browse.BrowseScreen
import dev.yourstream.ui.screens.browse.BrowseUiState
import dev.yourstream.ui.screens.search.SearchResults
import dev.yourstream.ui.screens.search.SearchScreen
import dev.yourstream.ui.screens.search.SearchUiState
import dev.yourstream.ui.screens.settings.SettingsScreen
import dev.yourstream.ui.screens.settings.SettingsUiState
import dev.yourstream.ui.core.StateTags
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import dev.yourstream.ui.screens.home.HomeUiState
import dev.yourstream.ui.screens.navigation.AdaptiveShell
import dev.yourstream.ui.screens.navigation.AppNavigator
import dev.yourstream.ui.screens.navigation.DetailsRoute
import dev.yourstream.ui.screens.navigation.HomeRoute
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.navigation.PlayerRoute
import dev.yourstream.ui.screens.navigation.TopLevel
import dev.yourstream.ui.screens.settings.AccountAction
import dev.yourstream.ui.screens.settings.AccountScreen
import dev.yourstream.ui.screens.settings.AccountStep
import dev.yourstream.ui.screens.settings.AccountUiState
import dev.yourstream.ui.screens.settings.SourceRow
import dev.yourstream.ui.screens.settings.SourcesAction
import dev.yourstream.ui.screens.settings.SourcesScreen
import dev.yourstream.ui.screens.settings.SourcesUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun set(layout: WindowLayout = WindowLayout.COMPACT, content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { YourStreamTheme(layout) { content() } }

    @androidx.compose.runtime.Composable
    private fun rows(vararg recent: Tile): HomeRows {
        val r = kotlinx.coroutines.flow.flowOf(PagingData.from(recent.toList())).collectAsLazyPagingItems()
        val none = kotlinx.coroutines.flow.flowOf(PagingData.from(emptyList<Tile>())).collectAsLazyPagingItems()
        return HomeRows(r, none)
    }

    @Test
    fun homeBlocksOnSignInThenShowsHeroAndRows() {
        var signIn = 0
        val opened = mutableListOf<OpenTarget>()
        var state by androidx.compose.runtime.mutableStateOf(HomeUiState(HomeBanner.NOT_SIGNED_IN, UiState.Empty(EmptyKind.LIBRARY_EMPTY), UiState.Empty(EmptyKind.NOTHING_IN_PROGRESS), libraryCount = 0))
        val tile = Tile(MediaCardModel("item:7", "Vikram", "2022"), OpenTarget.Details(7), itemId = 7, isFavorite = false, watched = false)
        set { HomeScreen(state, rows(tile), {}, { opened += it }, {}, { signIn++ }, {}) }
        compose.onNodeWithText("Connect Telegram").assertIsDisplayed()
        compose.onNodeWithTag("state-action").performClick()
        assertEquals(1, signIn)

        val hero = HeroItem(HeroItem.Kind.CONTINUE, "hero:5", "Ben 10", "2005", null, 0.35f, OpenTarget.Play(PlaybackTarget(5, 6)), OpenTarget.Details(5), "S01E06")
        state = HomeUiState(null, UiState.Content(hero), UiState.Error(UiError(ErrorKind.OFFLINE)), libraryCount = 12)
        compose.onNodeWithTag("hero").assertExists()
        compose.onNodeWithText("Resume S01E06").performClick()
        assertEquals(OpenTarget.Play(PlaybackTarget(5, 6)), opened.last())
        compose.onNodeWithText("Try again").assertExists() // the failed row shows its error in place
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("card:item:7"))
        compose.onNodeWithTag("card:item:7").performClick()
        assertEquals(OpenTarget.Details(7), opened.last())
    }

    @Test
    fun browseShowsCountAndSkeletonWhileLoading() {
        set {
            val loading = androidx.compose.runtime.remember {
                kotlinx.coroutines.flow.flowOf(PagingData.empty<Tile>(LoadStates(LoadState.Loading, LoadState.NotLoading(false), LoadState.NotLoading(false))))
            }
            BrowseScreen("Library", EmptyKind.LIBRARY_EMPTY, BrowseUiState(count = 1240), loading.collectAsLazyPagingItems(), {}, {})
        }
        compose.onNodeWithTag("count").assertTextEquals("1240 titles")
        compose.onNodeWithTag(StateTags.LOADING).assertExists() // skeleton, not a spinner
    }

    @Test
    fun browseShowsItsEmptyState() {
        set {
            // As Room's paging source reports it: refresh finished, end of pagination reached, no items.
            val done = LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))
            val empty = androidx.compose.runtime.remember { kotlinx.coroutines.flow.flowOf(PagingData.from(emptyList<Tile>(), done)) }
            BrowseScreen("Favorites", EmptyKind.NO_FAVORITES, BrowseUiState(count = 0), empty.collectAsLazyPagingItems(), {}, {})
        }
        compose.onNodeWithText("No favorites yet").assertExists()
        // Nothing to filter or count yet.
        compose.onNodeWithTag("count").assertDoesNotExist()
        compose.onNodeWithTag("sort").assertDoesNotExist()
    }

    @Test
    fun searchShowsTopResultAndCount() {
        val top = Tile(MediaCardModel("hit:1:0", "Doraemon"), OpenTarget.Details(1))
        val rest = listOf(Tile(MediaCardModel("hit:2:0", "Doraemon: Nobita"), OpenTarget.Details(2)))
        val opened = mutableListOf<OpenTarget>()
        set { SearchScreen(SearchUiState("doremon", UiState.Content(SearchResults(top, rest, 2)), interpretation = listOf("doremon → doraemon")), {}, { opened += it }) }
        compose.onNodeWithTag("results-count").assertTextEquals("2 results")
        compose.onNodeWithText("doremon → doraemon").assertExists()
        compose.onNodeWithTag("top-result").performClick()
        assertEquals(listOf<OpenTarget>(OpenTarget.Details(1)), opened)
    }

    @Test
    fun settingsGroupsAndVersion() {
        var sources = 0
        set { SettingsScreen(SettingsUiState("Asha", true, 3, "Tamil, English"), "1.2.0", {}, { sources++ }, {}) }
        compose.onNodeWithText("Asha").assertExists()
        compose.onNodeWithText("Telegram account").assertExists()
        compose.onNodeWithText("3 chats selected").performClick()
        assertEquals(1, sources)
        compose.onNodeWithTag("settings").performScrollToNode(hasText("Version 1.2.0"))
        compose.onNodeWithText("Version 1.2.0").assertExists()
        compose.onNodeWithText("Developer tools").assertDoesNotExist()
    }

    @Test
    fun settingsChangePlayerPreferences() {
        var prefs by androidx.compose.runtime.mutableStateOf(dev.yourstream.ui.screens.settings.AppSettings())
        set { SettingsScreen(SettingsUiState("Asha", true, 3, ""), "1.2.0", {}, {}, {}, prefs = prefs, onPrefs = { prefs = it }) }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-autoplay"))
        compose.onNodeWithTag("pref-autoplay").performClick()
        assertEquals(false, prefs.autoplayNext)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-seek"))
        compose.onNodeWithTag("pref-seek").performClick()
        compose.onNodeWithTag("choice:30 seconds").performClick()
        assertEquals(30, prefs.seekSeconds)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-theme"))
        compose.onNodeWithTag("pref-theme").performClick()
        compose.onNodeWithTag("choice:Dark").performClick()
        assertEquals(dev.yourstream.ui.screens.settings.ThemeMode.DARK, prefs.theme)
    }

    @Test
    fun detailsShowsResumeAndAddsToLibrary() {
        val actions = mutableListOf<DetailsAction>()
        val opened = mutableListOf<OpenTarget>()
        val play = OpenTarget.Play(PlaybackTarget(1))
        set {
            DetailsScreen(
                DetailsUiState(UiState.Content(DetailsContent(1, LibraryKind.MOVIE, "Vikram", 2022, null, listOf("Tamil"), false, 40, PrimaryAction.Resume(play, 3_723_000), emptyList(), emptyList()))),
                { actions += it }, { opened += it }, {},
            )
        }
        compose.onNodeWithText("Resume from 1:02:03").performClick()
        assertEquals(listOf<OpenTarget>(play), opened)
        compose.onNodeWithTag("my-library").performScrollTo().performClick()
        assertEquals(listOf<DetailsAction>(DetailsAction.ToggleLibrary), actions)
        compose.onNodeWithTag("favorite").assertDoesNotExist()
    }

    @Test
    fun signInFlowAndSourceToggles() {
        val account = mutableListOf<AccountAction>()
        var step by androidx.compose.runtime.mutableStateOf<AccountStep>(AccountStep.Phone)
        set { AccountScreen(AccountUiState(step), { account += it }, {}) }
        compose.onNodeWithTag("account-dial").performTextReplacement("+49")
        compose.onNodeWithText("Germany", substring = true).assertExists()
        compose.onNodeWithTag("account-input").performTextInput("170 1234567")
        compose.onNodeWithTag("account-submit").performClick()
        assertEquals(listOf<AccountAction>(AccountAction.Submit("+491701234567")), account)
        compose.onNodeWithTag("account-qr").performScrollTo().performClick()
        assertEquals(AccountAction.UseQr, account.last())
        step = AccountStep.Code("+49***67", 5)
        compose.onNodeWithText("We've sent an SMS with the code to +49***67.").assertExists()
        compose.onNodeWithTag("account-input").performTextInput("12345")
        assertEquals(AccountAction.Submit("12345"), account.last(), "a complete code submits by itself")
        compose.onNodeWithText("Wrong number?").performClick()
        assertEquals(AccountAction.ChangeNumber, account.last())
    }

    @Test
    fun sourcesToggle() {
        val actions = mutableListOf<SourcesAction>()
        set { SourcesScreen(SourcesUiState(UiState.Content(listOf(SourceRow(3, "Tamil Movies", false, null, false, null)))), { actions += it }, {}) }
        compose.onNodeWithTag("toggle:3").performClick()
        assertEquals(listOf<SourcesAction>(SourcesAction.Toggle(3, true)), actions)
    }

    @Test
    fun eachLayoutHasItsNavigation() {
        val selected = mutableListOf<TopLevel>()
        var layout by androidx.compose.runtime.mutableStateOf(WindowLayout.COMPACT)
        set { AdaptiveShell(layout, TopLevel.HOME, { selected += it }) { m -> Text("content", m) } }
        // Phone: bottom bar with the 5 primary destinations.
        compose.onNodeWithTag("nav:bar").assertExists()
        compose.onNodeWithTag("nav:MOVIES").assertDoesNotExist()
        compose.onNodeWithTag("nav:SEARCH").performClick()
        assertEquals(listOf(TopLevel.SEARCH), selected)
        // Tablet rail, large-screen drawer, TV drawer: every destination.
        for ((l, tag) in listOf(WindowLayout.MEDIUM to "nav:rail", WindowLayout.EXPANDED to "nav:drawer", WindowLayout.TV to "nav:tv")) {
            layout = l
            compose.onNodeWithTag(tag).assertExists()
            compose.onNodeWithTag("nav:MOVIES").assertExists()
        }
    }

    @Test
    fun typedRoutesNavigateWithArguments() {
        lateinit var nav: TestNavHostController
        compose.setContent {
            nav = TestNavHostController(ApplicationProvider.getApplicationContext()).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("home") }
                composable<DetailsRoute> { Text("details ${it.toRoute<DetailsRoute>().itemId}") }
                composable<PlayerRoute> { val r = it.toRoute<PlayerRoute>(); Text("player ${r.itemId}/${r.episodeId}/${r.mediaId}") }
            }
        }
        val navigator = AppNavigator(nav)
        compose.runOnIdle { navigator.open(OpenTarget.Details(42)) }
        compose.onNodeWithText("details 42").assertExists()
        compose.runOnIdle { navigator.open(OpenTarget.Play(PlaybackTarget(5, 6))) }
        compose.onNodeWithText("player 5/6/null").assertExists()
        compose.runOnIdle { navigator.replacePlayer(OpenTarget.Play(PlaybackTarget(5, 7), 9)) }
        compose.onNodeWithText("player 5/7/9").assertExists()
        compose.runOnIdle { navigator.back() }
        compose.onNodeWithText("details 42").assertExists() // "play next" replaced the player, it did not stack
    }
}
