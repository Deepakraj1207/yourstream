package dev.yourstream.ui.core

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.platform.testTag
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

class UiModelsTest {
    @Test
    fun posterLinksAreMadeLoadable() {
        kotlin.test.assertEquals("https://img.example.com/p.jpg", posterUrl("http://img.example.com/p.jpg"))
        kotlin.test.assertEquals("https://img.example.com/p.jpg", posterUrl("//img.example.com/p.jpg"))
        kotlin.test.assertEquals("https://img.example.com/p.jpg", posterUrl(" https://img.example.com/p.jpg "))
        kotlin.test.assertEquals(null, posterUrl("N/A"))
        kotlin.test.assertEquals(null, posterUrl("/relative.jpg"))
        kotlin.test.assertEquals(null, posterUrl(null))
    }

    @Test
    fun layoutBreakpoints() {
        assertEquals(WindowLayout.COMPACT, WindowLayout.of(411, false))
        assertEquals(WindowLayout.MEDIUM, WindowLayout.of(700, false))
        assertEquals(WindowLayout.EXPANDED, WindowLayout.of(1280, false))
        assertEquals(WindowLayout.TV, WindowLayout.of(960, true), "a TV is a TV whatever its width")
    }

    @Test
    fun errorsAndStates() {
        assertEquals(ErrorKind.OFFLINE, AppError.Network().toUiError().kind)
        assertEquals(UiError(ErrorKind.NOT_FOUND, "gone", retryable = false), AppError.NotFound("gone").toUiError())
        assertEquals(ErrorKind.SIGNED_OUT, AppError.Unauthorized().toUiError().kind)
        assertEquals(UiState.Empty(EmptyKind.NO_RESULTS), emptyList<Int>().toUiState(EmptyKind.NO_RESULTS))
        assertEquals(UiState.Content(listOf(1)), listOf(1).toUiState(EmptyKind.NO_RESULTS))
        assertEquals(UiState.Error(UiError(ErrorKind.SERVICE, "Too many requests, retry in 3s")), AppResult.Err(AppError.RateLimited(3)).toUiState())
        assertEquals(UiState.Content(2), UiState.Content(1).map { it + 1 })
    }
}

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
class UiComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun timelineScrubsByKeysOnceAndByTouch() {
        var starts = 0
        val ends = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            YourStreamTheme(WindowLayout.TV) {
                Timeline(60_000, 1_320_000, 300_000, { starts++ }, { ends += it }, { "${it / 1000}s" }, androidx.compose.ui.Modifier.testTag("timeline"))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("timeline").requestFocus()
        // Three presses are one scrub and one seek, after a short pause.
        repeat(3) { compose.onNodeWithTag("timeline").performKeyInput { pressKey(Key.DirectionRight) } }
        compose.onNodeWithTag("scrub-time", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("90s", useUnmergedTree = true).assertExists()
        assertEquals(1, starts)
        assertEquals(emptyList<Long>(), ends)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(90_000L), ends)
        // Centre commits at once; the position never goes below zero.
        repeat(8) { compose.onNodeWithTag("timeline").performKeyInput { pressKey(Key.DirectionLeft) } }
        compose.onNodeWithTag("timeline").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(90_000L, 0L), ends)
        // A tap at three quarters jumps there; screen readers can set the position too.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("timeline").performTouchInput { click(percentOffset(0.75f, 0.5f)) }
        assertEquals(990_000.0, ends.last().toDouble(), 20_000.0)
        compose.onNodeWithTag("timeline").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(600_000f) }
        assertEquals(600_000L, ends.last())
        assertEquals(4, starts)
    }

    @Test
    fun stateContentRendersEveryState() {
        var state by androidx.compose.runtime.mutableStateOf<UiState<String>>(UiState.Loading)
        var retries = 0; var actions = 0
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                StateContent(state, onRetry = { retries++ }, onEmptyAction = { actions++ }) { Text("content $it") }
            }
        }
        compose.onNodeWithTag(StateTags.LOADING).assertExists()
        state = UiState.Empty(EmptyKind.NO_SOURCES)
        compose.onNodeWithText("Choose your sources").assertExists()
        compose.onNodeWithText("Choose chats").performClick()
        assertEquals(1, actions)
        state = UiState.Error(UiError(ErrorKind.OFFLINE, "no network"))
        compose.onNodeWithTag(StateTags.ERROR).assertExists()
        compose.onNodeWithText("no network").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
        state = UiState.Error(UiError(ErrorKind.NOT_FOUND, retryable = false))
        compose.onNodeWithText("Try again").assertDoesNotExist()
        state = UiState.Content("ok")
        compose.onNodeWithText("content ok").assertExists()
    }

    @Test
    fun cardsAreAccessibleAndWorkWithKeyboardAndRemote() {
        val clicked = mutableListOf<String>()
        val items = listOf(MediaCardModel("a", "Vikram", "2022", progress = 0.4f), MediaCardModel("b", "Leo", "2023"), MediaCardModel("c", "Jailer"))
        compose.setContent {
            YourStreamTheme(WindowLayout.TV) {
                MediaRow("Recently added", items, { it }, { clicked += it.key })
            }
        }
        // Screen readers get one description per card, including progress.
        compose.onNodeWithContentDescription("Vikram, 2022, 40 percent watched").assertExists()
        // Remote: focus a card, centre/Enter activates it, D-pad right moves along the row.
        compose.onNodeWithTag("card:a").requestFocus()
        compose.onNodeWithTag("card:a").assertIsFocused()
        compose.onNodeWithTag("card:a").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("a"), clicked)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("card:b").assertIsFocused()
        compose.onNodeWithTag("card:b").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("a", "b"), clicked)
    }

    @Test
    fun chipRowSelects() {
        var selected by androidx.compose.runtime.mutableStateOf<String?>("x")
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                Row { ChipRow(listOf(ChipOption("x", "X"), ChipOption("y", "Y")), selected, { selected = it }) }
            }
        }
        compose.onNodeWithText("Y").performClick()
        assertEquals("y", selected)
    }

    @Test
    fun cardMenuOpensByLongPressMenuKeyAndScreenReader() {
        val done = mutableListOf<String>()
        var layout by androidx.compose.runtime.mutableStateOf(WindowLayout.COMPACT)
        compose.setContent {
            YourStreamTheme(layout) {
                PosterCard(MediaCardModel("a", "Vikram"), {}, actions = listOf(CardAction("Add to favorites") { done += "fav" }, CardAction("Mark watched") { done += "watched" }))
            }
        }
        // Touch: long press → bottom sheet.
        compose.onNodeWithTag("card:a").performTouchInput { longClick() }
        compose.onNodeWithTag("card-menu").assertExists()
        compose.onNodeWithText("Mark watched").performClick()
        assertEquals(listOf("watched"), done)
        compose.waitForIdle()

        // Screen readers: the same actions as custom accessibility actions.
        val custom = compose.onNodeWithTag("card:a").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        assertEquals(listOf("Add to favorites", "Mark watched"), custom.map { it.label })
        compose.runOnIdle { custom.first().action() }
        assertEquals(listOf("watched", "fav"), done)

        // Remote: the Menu key → a focusable dialog on TV.
        layout = WindowLayout.TV
        compose.onNodeWithTag("card:a").requestFocus()
        compose.onNodeWithTag("card:a").performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithTag("card-menu").assertExists()
        compose.onNodeWithText("Add to favorites").performClick()
        assertEquals(listOf("watched", "fav", "fav"), done)
    }

    @Test
    fun titleArtworkIsDerivedFromTheTitle() {
        assertEquals("DK", initials("The Dark Knight"))
        assertEquals("D", firstLetter("The Dark Knight"))
        assertEquals("L", firstLetter("Leo"))
        assertEquals("BI", initials("Bheem Island"))
        assertEquals(TitleColors.of("Vikram"), TitleColors.of("vikram"), "stable per title")
        assertEquals("1", initials("10"))
    }
}
