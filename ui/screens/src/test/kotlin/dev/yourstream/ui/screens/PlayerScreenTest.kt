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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextContains
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
    fun nuvioControlsLockPillsSourcesAndSubtitleStyle() {
        var settings by mutableStateOf(dev.yourstream.ui.screens.settings.AppSettings())
        state = base.copy(sources = listOf(
            dev.yourstream.ui.screens.player.SourceOption(1, "1080p · Tamil", "2.1 GB · Ben.10.1080p.mkv", playing = true),
            dev.yourstream.ui.screens.player.SourceOption(2, "720p · Hindi", "0.9 GB · Ben.10.720p.mkv", playing = false),
        ))
        compose.setContent {
            YourStreamTheme(WindowLayout.COMPACT) {
                androidx.compose.runtime.CompositionLocalProvider(dev.yourstream.ui.screens.settings.LocalAppSettings provides settings) {
                    val items = flowOf(PagingData.from(episodes, done)).collectAsLazyPagingItems()
                    PlayerScreen(state, { actions += it }, { backs++ }, { played += it }, items, onFit = {}, onSettings = { settings = it }) {
                        Box(Modifier.fillMaxSize().testTag("surface"))
                    }
                }
            }
        }
        // Labelled pills and time pills, like Nuvio's.
        compose.onNodeWithTag("speed").assertTextContains("1×", substring = true)
        compose.onNodeWithTag("subtitles").assertTextContains("Subs", substring = true)
        compose.onNodeWithTag("audio").assertTextContains("Audio", substring = true)
        compose.onNodeWithTag("position").assertTextContains("1:00")
        compose.onNodeWithTag("sources").assertTextContains("Sources (2)")

        // Sources: switching to the other file.
        compose.onNodeWithTag("sources").performClick()
        compose.onNodeWithTag("panel:sources").assertExists()
        compose.onNodeWithText("720p · Hindi", substring = true).performClick()
        assertEquals(PlayerAction.PlaySource(2), actions.last())

        // Subtitles → Style: changes are kept in the settings.
        compose.onNodeWithTag("subtitles").performClick()
        compose.onNodeWithTag("subtitle-style-open").performClick()
        compose.onNodeWithTag("style-color:Yellow").performClick()
        compose.onNodeWithTag("style-bold").performClick()
        assertEquals(dev.yourstream.ui.screens.settings.AppSettings.SUBTITLE_COLORS[1], settings.subtitleColor)
        assertEquals(true, settings.subtitleBold)
        compose.onNodeWithTag("style-reset").performScrollTo().performClick()
        assertEquals(false, settings.subtitleBold)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        // Lock: controls and gestures gone, back does nothing; the unlock button brings them back.
        compose.onNodeWithTag("player-lock").performClick()
        compose.onNodeWithTag("player-locked").assertExists()
        compose.onNodeWithTag("play-pause").assertDoesNotExist()
        compose.onNodeWithTag("player-gestures").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backs, "back does not leave a locked player")
        compose.onNodeWithTag("player-unlock").performClick()
        compose.onNodeWithTag("player-locked").assertDoesNotExist()
        compose.onNodeWithTag("play-pause").assertExists()
    }

    @Test
    fun swipeToSeekCoversMoreOfLongerVideos() {
        assertEquals(60_000L, dev.yourstream.ui.screens.player.swipeSeekSpanMs(22 * 60_000L))
        assertEquals(90_000L, dev.yourstream.ui.screens.player.swipeSeekSpanMs(45 * 60_000L))
        assertEquals(120_000L, dev.yourstream.ui.screens.player.swipeSeekSpanMs(150 * 60_000L))
        // Half the width forward in a 2½-hour film: +60 s; never before 0 or past the end.
        assertEquals(660_000L, dev.yourstream.ui.screens.player.swipeSeekTarget(600_000L, 150 * 60_000L, 0.5f))
        assertEquals(0L, dev.yourstream.ui.screens.player.swipeSeekTarget(10_000L, 22 * 60_000L, -1f))
        assertEquals(22 * 60_000L, dev.yourstream.ui.screens.player.swipeSeekTarget(22 * 60_000L - 5_000, 22 * 60_000L, 1f))
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

    /** Reported from a device: a spinner showed behind the Play button while buffering. */
    @Test
    fun bufferingRingsPlayInsteadOfHidingBehindIt() {
        state = base.copy(status = dev.yourstream.playback.media3.PlaybackStatus.BUFFERING)
        show(WindowLayout.COMPACT)
        // Both there: Play keeps its place (and still works), the ring is drawn around it.
        compose.onNodeWithTag("play-pause").assertExists().performClick()
        compose.onNodeWithTag("player-loading").assertExists()
        assertEquals(PlayerAction.TogglePlay, actions.last())
        val play = compose.onNodeWithTag("play-pause").fetchSemanticsNode().boundsInRoot
        val ring = compose.onNodeWithTag("player-loading").fetchSemanticsNode().boundsInRoot
        assertEquals(play.center.x, ring.center.x, 1f)
        assertEquals(play.center.y, ring.center.y, 1f)
        kotlin.test.assertTrue(ring.width > play.width, "the ring is outside the button")
    }
}
