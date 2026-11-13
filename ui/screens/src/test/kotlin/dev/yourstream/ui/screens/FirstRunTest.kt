package dev.yourstream.ui.screens

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.firstrun.FirstRunScreen
import dev.yourstream.ui.screens.firstrun.FirstRunStep
import dev.yourstream.ui.screens.firstrun.FirstRunStore
import dev.yourstream.ui.screens.firstrun.FirstRunUiState
import dev.yourstream.ui.screens.navigation.AppNavigator
import dev.yourstream.ui.screens.navigation.FirstRunRoute
import dev.yourstream.ui.screens.navigation.HomeRoute
import dev.yourstream.ui.screens.navigation.SettingsRoute
import dev.yourstream.ui.screens.navigation.TopLevel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The guided first run: its steps, skipping, the real links it opens, and when it is shown. */
@RunWith(RobolectricTestRunner::class)
class FirstRunTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        context.getSharedPreferences("first_run", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun set(state: () -> FirstRunUiState, connect: () -> Unit = {}, choose: () -> Unit = {}, done: () -> Unit = {}) =
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { FirstRunScreen(state(), connect, choose, done) } }

    /** Every tappable thing has a touch area of at least 48 × 48 dp. */
    private fun assertTouchTargets() {
        val small = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().filter { n ->
            with(compose.density) {
                (n.size.width.toDp().value < 47.5f || n.size.height.toDp().value < 47.5f) &&
                    (n.touchBoundsInRoot.width.toDp().value < 47.5f || n.touchBoundsInRoot.height.toDp().value < 47.5f)
            }
        }
        assertTrue(small.isEmpty(), "touch targets under 48 dp: ${small.map { it.size }}")
    }

    private fun next() = compose.onNodeWithTag("first-run-next").performClick()
    private fun onStep(step: FirstRunStep) = compose.onNodeWithTag("first-run:${step.name}").assertExists()

    @Test
    fun walksThroughEveryStepAndFinishes() {
        var state by mutableStateOf(FirstRunUiState(starting = false))
        var connects = 0
        var chooses = 0
        var done = 0
        set({ state }, connect = { connects++ }, choose = { chooses++ }, done = { done++ })

        onStep(FirstRunStep.WELCOME)
        compose.onNodeWithTag("first-run-progress").assertTextContains("Step 1 of 6")
        assertTouchTargets()
        next()

        // Telegram: not signed in → the real sign-in screen; "Not now" moves on without it.
        onStep(FirstRunStep.TELEGRAM)
        compose.onNodeWithTag("first-run-next").assertTextContains("Not now")
        assertTouchTargets()
        compose.onNodeWithTag("first-run-connect").performClick()
        assertEquals(1, connects)
        state = state.copy(signedIn = true, name = "Asha")
        compose.onNodeWithText("Connected as Asha").assertExists()
        compose.onNodeWithTag("first-run-connect").assertDoesNotExist()
        compose.onNodeWithTag("first-run-next").assertTextContains("Next")
        next()

        // Chats: the real chat picker; the count updates when chats are chosen.
        onStep(FirstRunStep.SOURCES)
        compose.onNodeWithText("No chats chosen yet").assertExists()
        compose.onNodeWithTag("first-run-choose").performClick()
        assertEquals(1, chooses)
        state = state.copy(sources = 2)
        compose.onNodeWithText("2 chats chosen").assertExists()
        next()

        // Indexing: live progress, then the result.
        onStep(FirstRunStep.INDEXING)
        state = state.copy(indexing = IndexingStatus(chats = 1, found = 12, scanned = 50, total = 200))
        compose.onNodeWithText("12 videos found so far").assertExists()
        state = state.copy(indexing = IndexingStatus(), files = 40)
        compose.onNodeWithText("40 videos found").assertExists()
        next()

        onStep(FirstRunStep.LIBRARY)
        next()
        onStep(FirstRunStep.PLAYBACK)
        compose.onNodeWithTag("first-run-skip").assertDoesNotExist()
        compose.onNodeWithTag("first-run-next").assertTextContains("Start watching")
        assertEquals(0, done)
        next()
        assertEquals(1, done)
    }

    @Test
    fun backGoesToThePreviousStepAndSkipEndsTheGuide() {
        var done = 0
        set({ FirstRunUiState(starting = false) }, done = { done++ })
        next(); next()
        onStep(FirstRunStep.SOURCES)
        compose.onNodeWithText("Connect Telegram first to see your chats.").assertExists()
        compose.onNodeWithTag("first-run-back").performClick()
        onStep(FirstRunStep.TELEGRAM)
        compose.onNodeWithTag("first-run-skip").performClick()
        assertEquals(1, done)
    }

    @Test
    fun telegramStepWhileStartingOrUnavailableOffersNoSignIn() {
        var state by mutableStateOf(FirstRunUiState(starting = true))
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { FirstRunScreen(state, {}, {}, {}, initialStep = FirstRunStep.TELEGRAM) } }
        compose.onNodeWithText("Getting Telegram ready…").assertExists()
        compose.onNodeWithTag("first-run-connect").assertDoesNotExist()
        // No API ids, error codes or provider reasons: one plain sentence.
        state = FirstRunUiState(starting = false, unavailable = true)
        compose.onNodeWithText("Telegram sign-in is not available in this build of the app.").assertExists()
        compose.onNodeWithTag("first-run-connect").assertDoesNotExist()
    }

    @Test
    fun theWelcomeStepSaysWhatStaysPrivate() {
        set({ FirstRunUiState() })
        compose.onNodeWithText("Your messages are never sent", substring = true).assertExists()
        compose.onNodeWithText("Signing out removes", substring = true).assertExists()
    }

    @Test
    fun shownOnceAndNotToSomeoneAlreadySetUp() {
        val store = FirstRunStore(context)
        assertTrue(store.shouldShow(hasSources = false), "new install")
        assertTrue(store.shouldShow(hasSources = false), "still not finished or skipped")
        store.markDone()
        assertFalse(store.shouldShow(hasSources = false))
        assertTrue(FirstRunStore(context).done, "kept across restarts")

        clear()
        val updating = FirstRunStore(context)
        assertFalse(updating.shouldShow(hasSources = true), "updating user who already reads chats")
        assertTrue(updating.done)
    }

    @Test
    fun finishingReturnsHomeFromWhereverItWasOpened() {
        lateinit var nav: TestNavHostController
        compose.setContent {
            nav = TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            NavHost(nav, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("home") }
                composable<SettingsRoute> { Text("settings") }
                composable<FirstRunRoute> { Text("first run") }
            }
        }
        val navigator = AppNavigator(nav)
        // First launch: over Home.
        compose.runOnIdle { navigator.firstRun() }
        compose.onNodeWithText("first run").assertExists()
        compose.runOnIdle { navigator.finishFirstRun() }
        compose.onNodeWithText("home").assertExists()
        // From Settings → About → Setup guide: ends on Home too, with nothing left behind it.
        compose.runOnIdle { navigator.top(TopLevel.SETTINGS); navigator.firstRun() }
        compose.onNodeWithText("first run").assertExists()
        compose.runOnIdle { navigator.finishFirstRun() }
        compose.onNodeWithText("home").assertExists()
        compose.runOnIdle { assertFalse(nav.currentBackStack.value.any { it.destination.route?.contains("FirstRun") == true }) }
    }
}
