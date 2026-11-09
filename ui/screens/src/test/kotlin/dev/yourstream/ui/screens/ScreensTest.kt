package dev.yourstream.ui.screens

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.media3.PlaybackStatus
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
import dev.yourstream.ui.screens.home.HomeUiState
import dev.yourstream.ui.screens.navigation.AdaptiveShell
import dev.yourstream.ui.screens.navigation.AppNavigator
import dev.yourstream.ui.screens.navigation.DetailsRoute
import dev.yourstream.ui.screens.navigation.HomeRoute
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.navigation.PlayerRoute
import dev.yourstream.ui.screens.navigation.TopLevel
import dev.yourstream.ui.screens.player.PlayerAction
import dev.yourstream.ui.screens.player.PlayerScreen
import dev.yourstream.ui.screens.player.PlayerUiState
import dev.yourstream.ui.screens.player.TrackOption
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

    @Test
    fun homeBlocksOnSignInAndOpensTiles() {
        var signIn = 0
        val opened = mutableListOf<OpenTarget>()
        var state by androidx.compose.runtime.mutableStateOf(
            HomeUiState(HomeBanner.NOT_SIGNED_IN, UiState.Empty(EmptyKind.NOTHING_IN_PROGRESS), UiState.Empty(EmptyKind.LIBRARY_EMPTY), UiState.Empty(EmptyKind.NO_FAVORITES)),
        )
        set { HomeScreen(state, {}, { opened += it }, {}, { signIn++ }, {}) }
        compose.onNodeWithText("Connect Telegram").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, signIn)

        val tile = Tile(MediaCardModel("item:7", "Vikram", "2022"), OpenTarget.Details(7))
        state = HomeUiState(null, UiState.Empty(EmptyKind.NOTHING_IN_PROGRESS), UiState.Content(listOf(tile)), UiState.Error(UiError(ErrorKind.OFFLINE)))
        compose.onNodeWithTag("row:Recently added").assertExists()
        compose.onNodeWithTag("row:Continue watching").assertDoesNotExist()
        compose.onNodeWithText("Try again").assertExists() // the failed row shows its error in place
        compose.onNodeWithTag("card:item:7").performClick()
        assertEquals(listOf<OpenTarget>(OpenTarget.Details(7)), opened)
    }

    @Test
    fun detailsShowsResumeAndTogglesFavorite() {
        val actions = mutableListOf<DetailsAction>()
        val opened = mutableListOf<OpenTarget>()
        val play = OpenTarget.Play(PlaybackTarget(1))
        set {
            DetailsScreen(
                DetailsUiState(UiState.Content(DetailsContent(1, LibraryKind.MOVIE, "Vikram", 2022, listOf("Tamil"), false, 40, PrimaryAction.Resume(play, 3_723_000), emptyList(), emptyList()))),
                { actions += it }, { opened += it }, {},
            )
        }
        compose.onNodeWithText("Resume from 1:02:03").performClick()
        assertEquals(listOf<OpenTarget>(play), opened)
        compose.onNodeWithTag("favorite").performClick()
        assertEquals(listOf<DetailsAction>(DetailsAction.ToggleFavorite), actions)
    }

    @Test
    fun playerControlsErrorsAndNextEpisode() {
        val actions = mutableListOf<PlayerAction>()
        val next = mutableListOf<OpenTarget.Play>()
        val target = OpenTarget.Play(PlaybackTarget(5, 6))
        var state by androidx.compose.runtime.mutableStateOf(
            PlayerUiState("Ben 10", PlaybackStatus.READY, isPlaying = true, positionMs = 60_000, durationMs = 1_320_000,
                audio = listOf(TrackOption("a1", "Hindi"), TrackOption("a2", "Tamil")), selectedAudio = "a1"),
        )
        set(WindowLayout.TV) { PlayerScreen(state, { actions += it }, {}, { next += it }) { Box(Modifier.testTag("surface")) } }
        compose.onNodeWithTag("play-pause").performClick()
        compose.onNodeWithText("+30 s").performClick()
        assertEquals(listOf(PlayerAction.TogglePlay, PlayerAction.SeekBy(30_000)), actions)
        compose.onNodeWithTag("audio").performClick()
        compose.onNodeWithText("Tamil").assertExists()

        state = state.copy(status = PlaybackStatus.ERROR, error = UiError(ErrorKind.OFFLINE, "timeout"), audio = emptyList())
        compose.onNodeWithText("Try again").performClick()
        assertEquals(PlayerAction.Retry, actions.last())

        state = state.copy(status = PlaybackStatus.ENDED, error = null, next = "S01E06" to target)
        compose.onNodeWithText("Play S01E06").performClick()
        assertEquals(listOf(target), next)
    }

    @Test
    fun signInFlowAndSourceToggles() {
        val account = mutableListOf<AccountAction>()
        var step by androidx.compose.runtime.mutableStateOf<AccountStep>(AccountStep.Phone)
        set { AccountScreen(AccountUiState(step), { account += it }, {}) }
        compose.onNodeWithTag("account-input").performTextInput("+491701234567")
        compose.onNodeWithTag("account-submit").performClick()
        assertEquals(listOf<AccountAction>(AccountAction.Submit("+491701234567")), account)
        step = AccountStep.Code("+49***67", 5)
        compose.onNodeWithText("Code sent to +49***67").assertExists()
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
