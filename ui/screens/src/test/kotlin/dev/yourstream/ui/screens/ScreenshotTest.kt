package dev.yourstream.ui.screens

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.browse.BrowseScreen
import dev.yourstream.ui.screens.browse.BrowseControls
import dev.yourstream.ui.screens.browse.BrowseUiState
import dev.yourstream.ui.screens.home.HeroItem
import dev.yourstream.ui.screens.home.HomeRows
import dev.yourstream.ui.screens.home.HomeScreen
import dev.yourstream.ui.screens.home.HomeUiState
import dev.yourstream.ui.screens.navigation.AdaptiveShell
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.navigation.TopLevel
import dev.yourstream.ui.screens.settings.SettingsScreen
import dev.yourstream.ui.screens.settings.SettingsUiState
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screen composables at phone, tablet and TV sizes into
 * `ui/screens/build/screenshots/` for visual review. The titles are test fixtures (this file only);
 * the app shows the user's own library. No network in tests: artwork shows the title placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val done = LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))

    private fun tile(id: Long, title: String, year: Int, badge: String? = null, progress: Float? = null, watched: Boolean = false) =
        Tile(MediaCardModel("item:$id", title, "$year", progress, badge, watched = watched), OpenTarget.Details(id), id, false, watched)

    private val movies = listOf(tile(1, "Vikram", 2022, "4K"), tile(2, "Jailer", 2023, "1080p"), tile(3, "Leo", 2023), tile(4, "Comali", 2019, watched = true),
        tile(5, "Ponniyin Selvan", 2022, "1080p"), tile(6, "Master", 2021), tile(7, "Kaithi", 2019, progress = 0.6f), tile(8, "Asuran", 2019))
    private val series = listOf(tile(20, "Ben 10", 2005), tile(21, "Doraemon", 1979, "+3"), tile(22, "Motu Patlu", 2012), tile(23, "Shinchan", 1992))
    private val continueTiles = listOf(
        Tile(MediaCardModel("cw:20:6", "Ben 10", "S01E06 · Washington B.C.", 0.35f), OpenTarget.Play(PlaybackTarget(20, 6)), 20, dismissible = true, caption = TileCaption.MinutesLeft(14)),
        Tile(MediaCardModel("cw:7:0", "Kaithi", "2019", 0.6f), OpenTarget.Play(PlaybackTarget(7)), 7, dismissible = true, caption = TileCaption.MinutesLeft(58)),
        Tile(MediaCardModel("cw:21:9", "Doraemon", "S02E01"), OpenTarget.Play(PlaybackTarget(21, 9)), 21, dismissible = true, caption = TileCaption.NewEpisode),
    )

    private fun shot(name: String, layout: WindowLayout, top: TopLevel, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent { YourStreamTheme(layout, darkTheme = dark) { androidx.compose.material3.Surface { AdaptiveShell(layout, top, {}) { content() } } } }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun home() {
        val r = flowOf(PagingData.from(movies, done)).collectAsLazyPagingItems()
        val s = flowOf(PagingData.from(series, done)).collectAsLazyPagingItems()
        val none = flowOf(PagingData.from(emptyList<Tile>(), done)).collectAsLazyPagingItems()
        val hero = HeroItem(HeroItem.Kind.CONTINUE, "hero:20", "Ben 10", "2005 · Washington B.C.", null, 0.35f, OpenTarget.Play(PlaybackTarget(20, 6)), OpenTarget.Details(20), "S01E06")
        HomeScreen(HomeUiState(null, UiState.Content(hero), UiState.Content(continueTiles), 240), HomeRows(r, r, s, none), {}, {}, {}, {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneHome() = shot("phone-home-dark", WindowLayout.COMPACT, TopLevel.HOME) { home() }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLibraryLight() = shot("phone-library-light", WindowLayout.COMPACT, TopLevel.LIBRARY, dark = false) {
        BrowseScreen("Library", EmptyKind.LIBRARY_EMPTY, BrowseUiState(count = 240), flowOf(PagingData.from(movies + series, done)).collectAsLazyPagingItems(), {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneFavoritesEmpty() = shot("phone-favorites-empty", WindowLayout.COMPACT, TopLevel.FAVORITES) {
        BrowseScreen("Favorites", EmptyKind.NO_FAVORITES, BrowseUiState(controls = BrowseControls(kind = true, sort = false, watch = false), count = 0), flowOf(PagingData.from(emptyList<Tile>(), done)).collectAsLazyPagingItems(), {}, {})
    }

    @Test @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun tabletHome() = shot("tablet-home", WindowLayout.EXPANDED, TopLevel.HOME) { home() }

    @Test @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun tvHome() = shot("tv-home", WindowLayout.TV, TopLevel.HOME) { home() }

    @Test @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun tvSettings() = shot("tv-settings", WindowLayout.TV, TopLevel.SETTINGS) {
        SettingsScreen(SettingsUiState("Asha", true, 3, "Tamil, English"), "0.1.0", {}, {}, {}, {})
    }

    // ---- Player (full screen: no shell). A gradient stands in for the video frame.

    private val playerState = dev.yourstream.ui.screens.player.PlayerUiState(
        "Ben 10", "S01E06 · Washington B.C.", dev.yourstream.playback.media3.PlaybackStatus.READY, isPlaying = false,
        positionMs = 463_000, durationMs = 1_320_000, bufferedMs = 640_000,
        quality = dev.yourstream.ui.screens.player.QualityInfo("1080p", "1920 × 1080", "HEVC", "23.976 fps", "4.2 Mbps", "AAC · Stereo", "Ben.10.S01E06.1080p.mkv", 690_000_000),
        audio = listOf(dev.yourstream.ui.screens.player.TrackOption("a1", "Hindi"), dev.yourstream.ui.screens.player.TrackOption("a2", "Tamil"), dev.yourstream.ui.screens.player.TrackOption("a3", "English")),
        selectedAudio = "a2",
        subtitles = listOf(dev.yourstream.ui.screens.player.TrackOption("s1", "English")),
        previous = dev.yourstream.ui.screens.player.EpisodeLink("S01E05", "Kevin 11", OpenTarget.Play(PlaybackTarget(20, 5))),
        next = dev.yourstream.ui.screens.player.EpisodeLink("S01E07", "The Krakken", OpenTarget.Play(PlaybackTarget(20, 7))),
        resumedFromMs = 455_000,
        episodes = dev.yourstream.ui.screens.player.EpisodesPanel(
            listOf(dev.yourstream.ui.screens.episodes.SeasonTab(1, "Season 1"), dev.yourstream.ui.screens.episodes.SeasonTab(2, "Season 2")), 1, 6,
        ),
    )
    private val playerEpisodes = listOf("And Then There Were 10", "Washington B.C.", "The Krakken", "Permanent Retirement", "Hunted", "Tourist Trap", "Kevin 11", "The Alliance")
        .mapIndexed { i, t ->
            val n = i + 4L
            dev.yourstream.ui.screens.episodes.EpisodeItem(n, "S01E%02d · %s".format(n, t), if (n == 6L) 0.35f else null, watched = n < 6, available = true, play = OpenTarget.Play(PlaybackTarget(20, n)))
        }

    private fun playerShot(name: String, layout: WindowLayout, state: dev.yourstream.ui.screens.player.PlayerUiState, open: String? = null) {
        compose.setContent {
            YourStreamTheme(layout, darkTheme = true) {
                val items = flowOf(PagingData.from(playerEpisodes, done)).collectAsLazyPagingItems()
                dev.yourstream.ui.screens.player.PlayerScreen(state, {}, {}, {}, items) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF1B3A4B), androidx.compose.ui.graphics.Color(0xFF3F2E56), androidx.compose.ui.graphics.Color(0xFF0B0D12))),
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
        open?.let { compose.onNodeWithTag(it).performClick(); compose.waitForIdle() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun phonePlayer() = playerShot("phone-player", WindowLayout.COMPACT, playerState)

    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun phonePlayerAudio() = playerShot("phone-player-audio", WindowLayout.COMPACT, playerState.copy(resumedFromMs = null), open = "audio")

    @Test @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun tvPlayer() = playerShot("tv-player", WindowLayout.TV, playerState.copy(resumedFromMs = null, status = dev.yourstream.playback.media3.PlaybackStatus.BUFFERING, recovery = "waiting for network"))

    @Test @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun tvPlayerEpisodes() = playerShot("tv-player-episodes", WindowLayout.TV, playerState.copy(resumedFromMs = null), open = "episodes")

    @Test @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun tvPlayerUpNext() = playerShot("tv-player-up-next", WindowLayout.TV, playerState.copy(resumedFromMs = null, status = dev.yourstream.playback.media3.PlaybackStatus.ENDED, positionMs = 1_320_000))

    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun phonePlayerError() = playerShot("phone-player-error", WindowLayout.COMPACT, playerState.copy(resumedFromMs = null, status = dev.yourstream.playback.media3.PlaybackStatus.ERROR,
        error = dev.yourstream.ui.core.UiError(dev.yourstream.ui.core.ErrorKind.OFFLINE, "Telegram is not reachable")))
}
