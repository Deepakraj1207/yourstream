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
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.yourstream.core.common.AppError
import androidx.compose.ui.test.performTextClearance
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
import dev.yourstream.ui.screens.settings.SettingsHost
import dev.yourstream.ui.screens.settings.SettingsPage
import dev.yourstream.ui.screens.settings.SettingsPageScreen
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
import dev.yourstream.ui.screens.navigation.MAIN_TABS
import dev.yourstream.ui.screens.navigation.MainNavigationBar
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun set(layout: WindowLayout = WindowLayout.COMPACT, content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { YourStreamTheme(layout) { content() } }

    /**
     * Every tappable thing on screen has a touch area of at least 48 × 48 dp (Material and Android
     * accessibility guidance), including the area Material adds around smaller visuals.
     */
    private fun assertTouchTargets() {
        val min = 48f - 0.5f // rounding of dp ↔ px
        val small = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().filter { n ->
            // Big enough by its own size (even when scrolled partly out of view, which clips the
            // touch bounds), or by its touch bounds (Material icon buttons draw 40 dp and extend
            // their touch area to 48 dp around it).
            val t = n.touchBoundsInRoot
            with(compose.density) {
                (n.size.width.toDp().value < min || n.size.height.toDp().value < min) &&
                    (t.width.toDp().value < min || t.height.toDp().value < min)
            }
        }
        assertTrue(small.isEmpty(), "touch targets under 48 dp: " + small.joinToString { n ->
            "${n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } ?: n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null } ?: n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null } ?: n.id} " +
                with(compose.density) { "${n.size.width.toDp()}×${n.size.height.toDp()}" }
        })
    }

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
        assertTouchTargets()
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
        assertTouchTargets()
    }

    @Test
    fun settingsHubListsCategoriesAndOpensThem() {
        val opened = mutableListOf<SettingsPage>()
        var account = 0
        set { SettingsScreen(SettingsHost(SettingsUiState("Asha", true, 3, "Tamil, English"), "1.2.0", onAccount = { account++ }), { opened += it }) }
        compose.onNodeWithText("Asha").performClick()
        assertEquals(1, account)
        compose.onNodeWithText("Telegram account").assertExists()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-page:PLAYBACK"))
        compose.onNodeWithTag("settings-page:PLAYBACK").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-page:ABOUT"))
        compose.onNodeWithText("Version 1.2.0").assertExists()
        compose.onNodeWithTag("settings-page:ABOUT").performClick()
        assertEquals(listOf(SettingsPage.PLAYBACK, SettingsPage.ABOUT), opened)
        compose.onNodeWithText("Developer tools").assertDoesNotExist()
        assertTouchTargets()
    }

    @Test
    fun settingsSearchFindsAndChangesASettingInPlace() {
        var prefs by androidx.compose.runtime.mutableStateOf(dev.yourstream.ui.screens.settings.AppSettings())
        set { SettingsScreen(SettingsHost(SettingsUiState("Asha", true, 3, ""), "1.2.0", prefs = prefs, onPrefs = { prefs = it })) }
        compose.onNodeWithTag("settings-search").performTextInput("amoled")
        compose.onNodeWithTag("pref-amoled").performClick()
        assertEquals(true, prefs.amoledBlack)
        compose.onNodeWithTag("pref-theme").assertDoesNotExist()
        compose.onNodeWithTag("settings-search").performTextClearance()
        compose.onNodeWithTag("settings-search").performTextInput("zzqx")
        compose.onNodeWithTag("settings-no-match").assertExists()
    }

    @Test
    fun settingsChangePlayerPreferences() {
        var prefs by androidx.compose.runtime.mutableStateOf(dev.yourstream.ui.screens.settings.AppSettings())
        var languages = 0
        set { SettingsPageScreen(SettingsPage.PLAYBACK, SettingsHost(SettingsUiState("Asha", true, 3, ""), "1.2.0", prefs = prefs, onPrefs = { prefs = it }, onLanguages = { languages++ }), {}) }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-autoplay"))
        compose.onNodeWithTag("pref-autoplay").performClick()
        assertEquals(false, prefs.autoplayNext)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-seek"))
        compose.onNodeWithTag("pref-seek").performClick()
        compose.onNodeWithTag("choice:30 seconds").performClick()
        assertEquals(30, prefs.seekSeconds)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("settings-languages"))
        compose.onNodeWithTag("settings-languages").performClick()
        assertEquals(1, languages)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-buffer"))
        compose.onNodeWithTag("pref-buffer").performClick()
        compose.onNodeWithTag("choice:5 minutes").performClick()
        assertEquals(300, prefs.streamBufferSeconds)
    }

    @Test
    fun settingsAppearanceLayoutAndReset() {
        var prefs by androidx.compose.runtime.mutableStateOf(dev.yourstream.ui.screens.settings.AppSettings())
        var resets = 0
        val host = { SettingsHost(SettingsUiState("Asha", true, 3, ""), "1.2.0", prefs = prefs, onPrefs = { prefs = it }, onReset = { resets++ }) }
        var page by androidx.compose.runtime.mutableStateOf(SettingsPage.APPEARANCE)
        set { SettingsPageScreen(page, host(), {}) }
        compose.onNodeWithTag("pref-theme").performClick()
        compose.onNodeWithTag("choice:Dark").performClick()
        assertEquals(dev.yourstream.ui.screens.settings.ThemeMode.DARK, prefs.theme)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-dock-labels"))
        compose.onNodeWithTag("pref-dock-labels").performClick()
        assertEquals(false, prefs.dockLabels)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-reduce-motion"))
        compose.onNodeWithTag("pref-reduce-motion").performClick()
        assertEquals(true, prefs.reduceMotion)
        page = SettingsPage.LAYOUT
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-home-hero"))
        compose.onNodeWithTag("pref-home-hero").performClick()
        assertEquals(false, prefs.homeRows.hero)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-poster-size"))
        compose.onNodeWithTag("pref-poster-size").performClick()
        compose.onNodeWithTag("choice:Large").performClick()
        assertEquals(dev.yourstream.ui.screens.settings.PosterSize.LARGE, prefs.posterSize)
        page = SettingsPage.ADVANCED
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-reset"))
        compose.onNodeWithTag("pref-reset").performClick()
        compose.onNodeWithTag("reset-confirm").performClick()
        assertEquals(1, resets)
    }

    @Test
    fun torrentStreamingIsOffByDefaultAndAsksBeforeTurningOn() {
        assertEquals(false, dev.yourstream.ui.screens.settings.AppSettings().torrentStreaming)
        var prefs by androidx.compose.runtime.mutableStateOf(dev.yourstream.ui.screens.settings.AppSettings())
        set { SettingsPageScreen(SettingsPage.PLAYBACK, SettingsHost(SettingsUiState("Asha", true, 3, ""), prefs = prefs, onPrefs = { prefs = it }), {}) }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-torrents"))
        // Cancel: stays off.
        compose.onNodeWithTag("pref-torrents").performClick()
        compose.onNodeWithTag("torrent-consent-cancel").performClick()
        assertEquals(false, prefs.torrentStreaming)
        // Agree: on.
        compose.onNodeWithTag("pref-torrents").performClick()
        compose.onNodeWithTag("torrent-consent-enable").performClick()
        assertEquals(true, prefs.torrentStreaming)
        // Turning off never asks.
        compose.onNodeWithTag("pref-torrents").performClick()
        compose.onNodeWithTag("torrent-consent-enable").assertDoesNotExist()
        assertEquals(false, prefs.torrentStreaming)
    }

    @Test
    fun signingOutAsksFirstAndSaysWhatIsRemoved() {
        val actions = mutableListOf<dev.yourstream.ui.screens.settings.AccountAction>()
        set { dev.yourstream.ui.screens.settings.AccountScreen(dev.yourstream.ui.screens.settings.AccountUiState(dev.yourstream.ui.screens.settings.AccountStep.SignedIn("Asha")), { actions += it }, {}) }
        compose.onNodeWithTag("account-sign-out").performClick()
        assertTrue(actions.isEmpty(), "one tap does not sign out")
        compose.onNodeWithText("Sign out of Telegram?").assertExists()
        compose.onNodeWithTag("account-sign-out-confirm").performClick()
        assertEquals(listOf<dev.yourstream.ui.screens.settings.AccountAction>(dev.yourstream.ui.screens.settings.AccountAction.LogOut), actions)
    }

    @Test
    fun aSessionEndedElsewhereIsExplainedOnTheSignInScreen() {
        val actions = mutableListOf<dev.yourstream.ui.screens.settings.AccountAction>()
        set {
            dev.yourstream.ui.screens.settings.AccountScreen(
                dev.yourstream.ui.screens.settings.AccountUiState(dev.yourstream.ui.screens.settings.AccountStep.Phone, signedOutNotice = true), { actions += it }, {},
            )
        }
        compose.onNodeWithText("You were signed out").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(listOf<dev.yourstream.ui.screens.settings.AccountAction>(dev.yourstream.ui.screens.settings.AccountAction.DismissNotice), actions)
    }

    @Test
    fun signInErrorsAreInPlainWords() {
        var error by androidx.compose.runtime.mutableStateOf<AppError>(AppError.RateLimited(3_600))
        set { dev.yourstream.ui.screens.settings.AccountScreen(dev.yourstream.ui.screens.settings.AccountUiState(dev.yourstream.ui.screens.settings.AccountStep.Phone, error = error), {}, {}) }
        compose.onNodeWithTag("account-error").assertTextEquals("Too many attempts. Telegram asks you to wait 1 hour before trying again.")
        error = AppError.Network("Request aborted")
        compose.onNodeWithTag("account-error").assertTextEquals("Can't reach Telegram. Check your internet connection and try again.")
        error = AppError.Telegram(400, "SOMETHING_NEW")
        compose.onNodeWithTag("account-error").assertTextEquals("Telegram couldn't do that. Try again in a moment. (Details: SOMETHING_NEW)")
    }

    @Test
    fun indexingCanBeLimitedToWifiAndAGoodBattery() {
        var policy by androidx.compose.runtime.mutableStateOf(dev.yourstream.media.api.model.IndexingPolicy())
        set { SettingsPageScreen(SettingsPage.CONTENT, SettingsHost(SettingsUiState("Asha", true, 3, ""), indexing = policy, onIndexing = { policy = it }), {}) }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-index-wifi"))
        compose.onNodeWithTag("pref-index-wifi").performClick()
        assertEquals(true, policy.wifiOnly)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-index-battery"))
        compose.onNodeWithTag("pref-index-battery").performClick()
        assertEquals(dev.yourstream.media.api.model.IndexingPolicy(wifiOnly = true, batteryAware = false), policy)
    }

    @Test
    fun backgroundPlaybackIsOnByDefaultAndCanBeTurnedOff() {
        assertEquals(true, dev.yourstream.ui.screens.settings.AppSettings().backgroundPlayback)
        var prefs by androidx.compose.runtime.mutableStateOf(dev.yourstream.ui.screens.settings.AppSettings())
        set { SettingsPageScreen(SettingsPage.PLAYBACK, SettingsHost(SettingsUiState("Asha", true, 3, ""), prefs = prefs, onPrefs = { prefs = it }), {}) }
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("pref-background"))
        compose.onNodeWithTag("pref-background").performClick()
        assertEquals(false, prefs.backgroundPlayback)
    }

    @Test
    fun aRemovedTitleHasNoPlayButtonAndSaysWhy() {
        set {
            DetailsScreen(
                DetailsUiState(UiState.Content(DetailsContent(1, LibraryKind.MOVIE, "Vikram", 2022, null, emptyList(), false, 40, primary = null,
                    versions = emptyList(), seasons = emptyList(), available = false, matched = false))),
                {}, {}, {},
            )
        }
        compose.onNodeWithTag("primary").assertDoesNotExist()
        compose.onNodeWithTag("details-removed").assertExists()
        compose.onNodeWithTag("details-not-matched").assertExists()
    }

    @Test
    fun aDownloadedVersionSaysItIsOnThisDevice() {
        val play = OpenTarget.Play(PlaybackTarget(1), 7)
        set {
            DetailsScreen(
                DetailsUiState(UiState.Content(DetailsContent(1, LibraryKind.MOVIE, "Vikram", 2022, null, emptyList(), false, 0, PrimaryAction.Play(play),
                    versions = listOf(dev.yourstream.ui.screens.details.VersionRow(7, "Vikram.2022.1080p.mkv", "1080p · 2.1 GB", play, onDevice = true)),
                    seasons = emptyList(), matched = true))),
                {}, {}, {},
            )
        }
        compose.onNodeWithTag("details").performScrollToNode(hasTestTag("version:7"))
        compose.onNodeWithText("1080p · 2.1 GB · On this device").assertExists()
        compose.onNodeWithTag("details-not-matched").assertDoesNotExist()
    }

    @Test
    fun theLibraryTabCanShowRemovedTitles() {
        val actions = mutableListOf<dev.yourstream.ui.screens.browse.BrowseAction>()
        val items = kotlinx.coroutines.flow.flowOf(PagingData.from(emptyList<Tile>()))
        set {
            BrowseScreen("Library", EmptyKind.MY_LIBRARY_EMPTY,
                BrowseUiState(controls = dev.yourstream.ui.screens.browse.BrowseControls(kind = true, sort = true, watch = true, playable = true), count = 3),
                items.collectAsLazyPagingItems(), { actions += it }, {})
        }
        compose.onNodeWithTag("playable-only").performClick()
        assertEquals(listOf<dev.yourstream.ui.screens.browse.BrowseAction>(dev.yourstream.ui.screens.browse.BrowseAction.SetPlayableOnly(false)), actions)
        // The filter maps to the domain query.
        assertEquals(true, dev.yourstream.ui.screens.browse.BrowseFilters(playableOnly = false).toQuery(null).includeUnavailable)
        assertEquals(false, dev.yourstream.ui.screens.browse.BrowseFilters().toQuery(null).includeUnavailable)
    }

    @Test
    fun settingsContentPageOpensSources() {
        var sources = 0
        set { SettingsPageScreen(SettingsPage.CONTENT, SettingsHost(SettingsUiState("Asha", true, 3, ""), onSources = { sources++ }), {}) }
        compose.onNodeWithText("3 chats selected").performClick()
        assertEquals(1, sources)
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
        assertTouchTargets()
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
        // Phone: a Material 3 navigation bar with the six main tabs.
        compose.onNodeWithTag("nav:bar").assertExists()
        for (t in MAIN_TABS) compose.onNodeWithTag("nav:${t.name}").assertExists()
        compose.onNodeWithTag("nav:CONTINUE").assertDoesNotExist()
        compose.onNodeWithTag("nav:HOME").assertIsSelected()
        assertTouchTargets()
        compose.onNodeWithTag("nav:SEARCH").performClick()
        compose.onNodeWithTag("nav:MOVIES").performClick()
        assertEquals(listOf(TopLevel.SEARCH, TopLevel.MOVIES), selected)
        // Tablet rail, large-screen drawer, TV drawer: the same six.
        for ((l, tag) in listOf(WindowLayout.MEDIUM to "nav:rail", WindowLayout.EXPANDED to "nav:drawer", WindowLayout.TV to "nav:tv")) {
            layout = l
            compose.onNodeWithTag(tag).assertExists()
            for (t in MAIN_TABS) compose.onNodeWithTag("nav:${t.name}").assertExists()
            compose.onNodeWithTag("nav:RECENT").assertDoesNotExist()
        }
    }

    @Test
    fun continueAndRecentPagesShowHomeAsTheSelectedTab() {
        set { AdaptiveShell(WindowLayout.COMPACT, TopLevel.RECENT, {}) { m -> Text("content", m) } }
        compose.onNodeWithTag("nav:HOME").assertIsSelected()
    }

    @Test
    fun tabNamesStayReadableWithoutLabels() {
        // Settings → Appearance → Tab names off: only the selected tab shows its name, but screen
        // readers still get every tab's name.
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(dev.yourstream.ui.screens.settings.LocalAppSettings provides dev.yourstream.ui.screens.settings.AppSettings(dockLabels = false)) {
                YourStreamTheme(WindowLayout.COMPACT) { MainNavigationBar(TopLevel.HOME, {}) }
            }
        }
        compose.onNodeWithTag("nav:SEARCH").assert(androidx.compose.ui.test.hasText("Search"))
        compose.onNodeWithTag("nav:SETTINGS").assert(androidx.compose.ui.test.hasText("Settings"))
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
