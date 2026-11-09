package dev.yourstream.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.media3.PlaybackStatus
import dev.yourstream.ui.core.ErrorKind
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.episodes.EpisodeItem
import dev.yourstream.ui.screens.episodes.SeasonTab
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.player.EpisodeLink
import dev.yourstream.ui.screens.player.EpisodesPanel
import dev.yourstream.ui.screens.player.PlayerAction
import dev.yourstream.ui.screens.player.PlayerScreen
import dev.yourstream.ui.screens.player.PlayerUiState
import dev.yourstream.ui.screens.player.QualityInfo
import dev.yourstream.ui.screens.player.TrackOption
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp")
class PlayerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<PlayerAction>()
    private val played = mutableListOf<OpenTarget.Play>()
    private var backs = 0

    private fun episode(n: Long) = OpenTarget.Play(PlaybackTarget(5, n))

    private val base = PlayerUiState(
        "Ben 10", "S01E06 · Washington B.C.", PlaybackStatus.READY, isPlaying = false,
        positionMs = 60_000, durationMs = 1_320_000, bufferedMs = 300_000,
        quality = QualityInfo("1080p", "1920 × 1080", "HEVC", "23.976 fps", audio = "AAC · Stereo"),
        audio = listOf(TrackOption("a1", "Hindi"), TrackOption("a2", "Tamil")), selectedAudio = "a1",
        subtitles = listOf(TrackOption("s1", "English")),
        previous = EpisodeLink("S01E05", "Pilot", episode(5)),
        next = EpisodeLink("S01E07", "Hero Time", episode(7)),
    )
    private var state by mutableStateOf(base)

    private val done = LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))
    private val episodes = (5L..7L).map { EpisodeItem(it, "S01E%02d".format(it), null, watched = it == 5L, available = true, play = episode(it)) }

    /**
     * With the test clock paused, state written outside composition (key handlers, the test's own
     * `state`) is announced on the next looper turn; announce it now, then let [ms] pass.
     */
    private fun settle(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
    }

    private fun show(layout: WindowLayout) = compose.setContent {
        YourStreamTheme(layout) {
            val items = flowOf(PagingData.from(episodes, done)).collectAsLazyPagingItems()
            PlayerScreen(state, { actions += it }, { backs++ }, { played += it }, items) { Box(Modifier.fillMaxSize().testTag("surface")) }
        }
    }

    @Test
    fun tvTransportPanelsAndBack() {
        show(WindowLayout.TV)
        compose.onNodeWithTag("play-pause").assertIsFocused()
        compose.onNodeWithTag("play-pause").performClick()
        compose.onNodeWithTag("forward").performClick()
        compose.onNodeWithTag("rewind").performClick()
        assertEquals(listOf<PlayerAction>(PlayerAction.TogglePlay, PlayerAction.SeekBy(10_000), PlayerAction.SeekBy(-10_000)), actions)
        compose.onNodeWithTag("next-episode").performClick()
        compose.onNodeWithTag("previous-episode").performClick()
        assertEquals(listOf(episode(7), episode(5)), played)

        // Audio: the selected track is offered first; choosing closes the panel.
        compose.onNodeWithTag("audio").performClick()
        compose.onNodeWithTag("option:Hindi").assertIsFocused()
        compose.onNodeWithTag("option:Tamil").performClick()
        assertEquals(PlayerAction.SelectAudio("a2"), actions.last())
        compose.onNodeWithTag("panel:audio").assertDoesNotExist()

        compose.onNodeWithTag("subtitles").performClick()
        compose.onNodeWithTag("option:Off").performClick()
        assertEquals(PlayerAction.SelectSubtitle(null), actions.last())

        compose.onNodeWithTag("speed").performClick()
        compose.onNodeWithTag("option:1.5×").performClick()
        assertEquals(PlayerAction.SetSpeed(1.5f), actions.last())

        compose.onNodeWithTag("quality-badge").assertExists()
        compose.onNodeWithText("1080p · HEVC").performClick()
        compose.onNodeWithText("1080p · 1920 × 1080").assertExists()
        compose.onNodeWithText("HEVC · 23.976 fps").assertExists()

        // BACK closes the panel first, then (paused) leaves the player.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("panel:info").assertDoesNotExist()
        assertEquals(0, backs)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backs)
    }

    @Test
    fun controlsHideWhilePlayingAndTheRemoteStillWorks() {
        state = base.copy(isPlaying = true)
        compose.mainClock.autoAdvance = false
        show(WindowLayout.TV)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("play-pause").assertExists()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("play-pause").assertDoesNotExist()

        // Hidden controls: left/right seek with a running total, centre toggles and shows them.
        compose.onNodeWithTag("player").performKeyInput { pressKey(Key.DirectionRight) }
        settle(100)
        compose.onNodeWithTag("seek-flash").assertExists()
        compose.onNodeWithText("+10 s").assertExists()
        compose.onNodeWithTag("player").performKeyInput { pressKey(Key.DirectionRight) }
        settle(100)
        compose.onNodeWithText("+20 s").assertExists()
        assertEquals(listOf<PlayerAction>(PlayerAction.SeekBy(10_000), PlayerAction.SeekBy(10_000)), actions)
        compose.onNodeWithTag("player").performKeyInput { pressKey(Key.MediaNext) }
        assertEquals(listOf(episode(7)), played)
        compose.onNodeWithTag("player").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(PlayerAction.TogglePlay, actions.last())
        settle(500)
        compose.onNodeWithTag("play-pause").assertExists()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp")
    fun touchGesturesResumeAndEpisodeList() {
        state = base.copy(
            resumedFromMs = 754_000,
            episodes = EpisodesPanel(listOf(SeasonTab(1, "Season 1"), SeasonTab(2, "Season 2")), 1, currentEpisodeId = 6),
        )
        show(WindowLayout.COMPACT)
        compose.onNodeWithText("Resumed from 12:34").assertExists()
        compose.onNodeWithTag("start-over").performClick()
        assertEquals(PlayerAction.StartOver, actions.last())

        compose.onNodeWithTag("player-gestures").performTouchInput { doubleClick(percentOffset(0.92f, 0.5f)) }
        compose.onNodeWithTag("player-gestures").performTouchInput { doubleClick(percentOffset(0.08f, 0.5f)) }
        assertEquals(listOf<PlayerAction>(PlayerAction.SeekBy(10_000), PlayerAction.SeekBy(-10_000)), actions.takeLast(2))

        compose.onNodeWithTag("episodes").performClick()
        compose.onNodeWithTag("panel:episodes").assertExists()
        compose.onNodeWithText("Now playing").assertExists()
        compose.onNodeWithTag("episode:6").assertIsNotEnabled() // the playing one
        compose.onNodeWithTag("episode:7").performClick()
        assertEquals(episode(7), played.last())
        compose.onNodeWithTag("chip:Season 2").performClick()
        assertEquals(PlayerAction.SelectSeason(2), actions.last())
    }

    @Test
    fun errorsOfferRetryAndSkipThenUpNextCountsDown() {
        compose.mainClock.autoAdvance = false
        state = base.copy(status = PlaybackStatus.ERROR, error = UiError(ErrorKind.OFFLINE, "timeout"))
        show(WindowLayout.TV)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("play-pause").assertDoesNotExist() // the error is on its own, not under the controls
        compose.onNodeWithText("Try again").performClick()
        assertEquals(PlayerAction.Retry, actions.last())
        compose.onNodeWithTag("error-skip").performClick()
        assertEquals(episode(7), played.last())

        // End of an episode: "Up next" counts down, Cancel stops it.
        state = base.copy(status = PlaybackStatus.ENDED, positionMs = 1_320_000)
        settle(3_500)
        compose.onNodeWithText("Playing in 7 s").assertExists()
        compose.onNodeWithTag("up-next-cancel").performClick()
        settle(10_000)
        compose.onNodeWithTag("up-next").assertDoesNotExist()
        assertEquals(1, played.size)

        // A new next episode offers again; left alone, it plays.
        state = state.copy(next = EpisodeLink("S01E08", null, episode(8)))
        settle(11_000)
        assertEquals(episode(8), played.last())
    }

    @Test
    fun upNextAppearsNearTheEndWithoutCountingDown() {
        state = base.copy(isPlaying = false, positionMs = 1_300_000)
        show(WindowLayout.COMPACT)
        compose.onNodeWithTag("up-next").assertExists()
        compose.onNodeWithTag("up-next-countdown").assertDoesNotExist()
        compose.onNodeWithTag("play-next").performClick()
        assertTrue(played.single() == episode(7))
    }
}
