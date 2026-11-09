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
import dev.yourstream.ui.screens.home.HomeBanner
import dev.yourstream.ui.screens.home.ForYou
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
        val forYou = ForYou("Tamil, action", listOf(
            Tile(MediaCardModel("fy:30", "Pushpa", "2021"), OpenTarget.Details(30), 30, caption = TileCaption.Reason("Telugu · action · ★ 7.6")),
            Tile(MediaCardModel("fy:31", "Asuran", "2019"), OpenTarget.Details(31), 31, caption = TileCaption.Reason("Tamil · drama · ★ 8.4")),
            Tile(MediaCardModel("fy:32", "Super Deluxe", "2019"), OpenTarget.Details(32), 32, caption = TileCaption.Reason("Tamil · ★ 8.3")),
            Tile(MediaCardModel("fy:33", "Soorarai Pottru", "2020"), OpenTarget.Details(33), 33, caption = TileCaption.Reason("Tamil · drama · ★ 8.7")),
        ))
        HomeScreen(HomeUiState(HomeBanner.REVIEW, UiState.Content(hero), UiState.Content(continueTiles), 240, UiState.Content(forYou), 12), HomeRows(r, s, none), {}, {}, {}, {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneHome() = shot("phone-home-dark", WindowLayout.COMPACT, TopLevel.HOME) { home() }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLibraryLight() = shot("phone-library-light", WindowLayout.COMPACT, TopLevel.LIBRARY, dark = false) {
        BrowseScreen("Library", EmptyKind.LIBRARY_EMPTY, BrowseUiState(count = 240), flowOf(PagingData.from(movies + series, done)).collectAsLazyPagingItems(), {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLibraryDark() = shot("phone-library-dark", WindowLayout.COMPACT, TopLevel.LIBRARY) {
        BrowseScreen("Library", EmptyKind.LIBRARY_EMPTY, BrowseUiState(dev.yourstream.ui.screens.browse.BrowseFilters(kind = dev.yourstream.library.api.LibraryKind.MOVIE), count = 240),
            flowOf(PagingData.from(movies + series, done)).collectAsLazyPagingItems(), {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSignIn() = shot("phone-sign-in", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        dev.yourstream.ui.screens.settings.AccountScreen(dev.yourstream.ui.screens.settings.AccountUiState(dev.yourstream.ui.screens.settings.AccountStep.Phone), {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSignInCode() = shot("phone-sign-in-code", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        dev.yourstream.ui.screens.settings.AccountScreen(dev.yourstream.ui.screens.settings.AccountUiState(dev.yourstream.ui.screens.settings.AccountStep.Code("+91 98765 43210", 5, inApp = true)), {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSignInQr() = shot("phone-sign-in-qr", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        dev.yourstream.ui.screens.settings.AccountScreen(dev.yourstream.ui.screens.settings.AccountUiState(dev.yourstream.ui.screens.settings.AccountStep.Qr("tg://login?token=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA")), {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneHomeIndexing() = shot("phone-home-indexing", WindowLayout.COMPACT, TopLevel.HOME) {
        val r = flowOf(PagingData.from(movies, done)).collectAsLazyPagingItems()
        val none = flowOf(PagingData.from(emptyList<Tile>(), done)).collectAsLazyPagingItems()
        val catalog = dev.yourstream.ui.screens.home.CatalogRow("cat:1", "Tamil Movies", series.map { it.copy(caption = TileCaption.InLibrary) })
        HomeScreen(
            HomeUiState(null, UiState.Empty(EmptyKind.LIBRARY_EMPTY), UiState.Empty(EmptyKind.NOTHING_IN_PROGRESS), 240, UiState.Empty(EmptyKind.GENERIC), 0,
                IndexingStatus(chats = 2, found = 134, scanned = 900, total = 2400), catalogs = listOf(catalog)),
            HomeRows(r, none, none), {}, {}, {}, {}, {},
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneAddons() = shot("phone-addons", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        dev.yourstream.ui.screens.settings.AddonsScreen(
            dev.yourstream.ui.screens.settings.AddonsUiState(listOf(
                dev.yourstream.ui.screens.settings.AddonRow(dev.yourstream.catalog.sync.CatalogAddon("indiastreams", "IndiaStreams", null, true, true), 9, true),
                dev.yourstream.ui.screens.settings.AddonRow(dev.yourstream.catalog.sync.CatalogAddon("indian_regional", "Indian Regional Catalog", null, true, false)),
                dev.yourstream.ui.screens.settings.AddonRow(dev.yourstream.catalog.sync.CatalogAddon("custom:x", "Cinemeta Catalogs", "https://v3-cinemeta.strem.io/manifest.json", false, true), 6, true),
            ), added = "Cinemeta Catalogs"), {}, {},
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneDetails() = shot("phone-details", WindowLayout.COMPACT, TopLevel.LIBRARY) {
        dev.yourstream.ui.screens.details.DetailsScreen(
            dev.yourstream.ui.screens.details.DetailsUiState(UiState.Content(dev.yourstream.ui.screens.details.DetailsContent(
                1, dev.yourstream.library.api.LibraryKind.MOVIE, "Vikram", 2022, null, listOf("Tamil"), false, 40,
                dev.yourstream.ui.screens.details.PrimaryAction.Resume(OpenTarget.Play(PlaybackTarget(1)), 3_723_000), emptyList(), emptyList()))),
            {}, {}, {},
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLibraryEmpty() = shot("phone-library-empty", WindowLayout.COMPACT, TopLevel.LIBRARY) {
        BrowseScreen("Library", EmptyKind.MY_LIBRARY_EMPTY, BrowseUiState(count = 0), flowOf(PagingData.from(emptyList<Tile>(), done)).collectAsLazyPagingItems(), {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneCatalogTitleSeries() = shot("phone-catalog-title", WindowLayout.COMPACT, TopLevel.HOME) {
        val ref = dev.yourstream.catalog.api.CatalogItemRef(dev.yourstream.catalog.api.CatalogProviderId("cinemeta"), dev.yourstream.catalog.api.CatalogType.SERIES, "tt0903747")
        val videos = (1..4).map { dev.yourstream.catalog.api.CatalogVideo("tt0903747:1:$it", 1, it, listOf("Pilot", "Cat's in the Bag", "And the Bag's in the River", "Cancer Man")[it - 1], "2008-01-2$it") }
        val ep = videos[1]
        dev.yourstream.ui.screens.addon.CatalogTitleScreen(
            dev.yourstream.ui.screens.addon.CatalogTitleUiState(
                "Breaking Bad", 2008, null, series = true,
                meta = dev.yourstream.catalog.api.CatalogMetadata(ref, "Breaking Bad", 2008, "A chemistry teacher diagnosed with cancer turns to making methamphetamine.",
                    genres = listOf("Crime", "Drama"), rating = 9.5, runtimeMinutes = 49, videos = videos),
                loadingMeta = false, seasons = listOf(1, 2, 3), season = 1, episode = ep,
                streams = listOf(dev.yourstream.ui.screens.addon.StreamGroup("Example Streams", listOf(
                    dev.yourstream.catalog.api.AddonStream("Example Streams", "1080p", "Breaking.Bad.S01E02.1080p.mkv · 1.2 GB", "https://example.com/a.mkv", null, null),
                    dev.yourstream.catalog.api.AddonStream("Example Streams", "720p", "HLS", "https://example.com/a.m3u8", null, null),
                    dev.yourstream.catalog.api.AddonStream("Example Streams", "4K", "Torrent", null, null, "abc"),
                ))),
            ),
            {}, {}, {}, {},
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSettings() = shot("phone-settings", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        SettingsScreen(SettingsUiState("Asha Kumar", true, 3, "Tamil, English", reviewCount = 4), "0.5.1", {}, {}, {},
            dev.yourstream.ui.screens.settings.SettingsLinks({}, {}, {}, {}, {}), updateLine = "Version 0.5.1 · up to date", onUpdate = {}, onClearCache = {}, onSourceCode = {})
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
        SettingsScreen(SettingsUiState("Asha", true, 3, "Tamil, English"), "0.1.0", {}, {}, {})
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
                dev.yourstream.ui.screens.player.PlayerScreen(state, {}, {}, {}, items, onRotate = if (layout == WindowLayout.TV) null else ({}), onFit = {}) {
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

    // ---- Advanced features

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneLibraryStatus() = shot("phone-library-status", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        dev.yourstream.ui.screens.library.LibraryStatusScreen(
            dev.yourstream.ui.screens.library.LibraryStatus(
                sources = listOf(
                    dev.yourstream.ui.screens.library.SourceSync(1, "Tamil Movies HD", false, 980, 0, null, null),
                    dev.yourstream.ui.screens.library.SourceSync(2, "Kids Cartoons", true, 260, 0, null, 42),
                ),
                files = 1240, titles = 240, movies = 180, series = 60, matched = 204, review = 12, unmatched = 18, providers = listOf("TMDB", "Cinemeta"),
            ), {}, {}, {}, {}, now = 5 * 60_000,
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneReview() = shot("phone-review", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        fun c(id: String, title: String, year: Int, pct: Int) = dev.yourstream.ui.screens.library.MatchCandidate(
            dev.yourstream.metadata.api.MetadataRef(dev.yourstream.metadata.api.ProviderId("tmdb"), dev.yourstream.metadata.api.MetadataType.MOVIE, id), title, year, null, null, pct)
        dev.yourstream.ui.screens.library.MatchScreen(
            "Check matches",
            dev.yourstream.ui.screens.library.MatchUiState(UiState.Content(listOf(
                dev.yourstream.ui.screens.library.MatchEntry(1, "Vikram", 2022, listOf(c("1", "Vikram", 2022, 71), c("2", "Vikram", 1986, 64), c("3", "Vikram Vedha", 2017, 58))),
                dev.yourstream.ui.screens.library.MatchEntry(2, "Master", null, listOf(c("4", "Master", 2021, 62), c("5", "The Master", 2012, 57))),
            ))),
            {}, {},
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneStats() = shot("phone-stats", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        val today = java.time.LocalDate.of(2026, 11, 9)
        val minutes = listOf(0, 45, 90, 0, 22, 130, 60, 0, 0, 44, 88, 150, 66, 95)
        dev.yourstream.ui.screens.stats.StatsScreen(UiState.Content(dev.yourstream.watch.core.WatchStats(
            totalMs = 61 * 3_600_000L, last7DaysMs = 493 * 60_000L, previous7DaysMs = 347 * 60_000L,
            daily = minutes.mapIndexed { i, m -> dev.yourstream.watch.core.DayTotal(today.minusDays((13 - i).toLong()), m * 60_000L) },
            moviesFinished = 9, episodesFinished = 47, titlesWatched = 23, sessions = 84, averageSessionMs = 43 * 60_000,
            currentStreakDays = 5, longestStreakDays = 11,
            topLanguages = listOf(dev.yourstream.watch.core.Share("ta", 1, 0.58f), dev.yourstream.watch.core.Share("hi", 1, 0.27f), dev.yourstream.watch.core.Share("en", 1, 0.15f)),
            topGenres = listOf(dev.yourstream.watch.core.Share("action", 1, 0.44f), dev.yourstream.watch.core.Share("animation", 1, 0.31f)),
            busiestHour = 21,
        )), {})
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSync() = shot("phone-sync", WindowLayout.COMPACT, TopLevel.SETTINGS) {
        dev.yourstream.ui.screens.sync.ProgressSyncScreen(
            dev.yourstream.ui.screens.sync.SyncUiState(dev.yourstream.sync.SyncStatus(enabled = true, lastSyncAt = 0, lastFrom = "Google Chromecast", lastPulled = 3, shared = 128)),
            {}, {}, now = 4 * 60_000,
        )
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSearchBrain() = shot("phone-search-brain", WindowLayout.COMPACT, TopLevel.SEARCH) {
        val tiles = movies.take(4).map { it.copy(caption = TileCaption.Reason("Tamil · unwatched")) }
        dev.yourstream.ui.screens.search.SearchScreen(
            dev.yourstream.ui.screens.search.SearchUiState(
                "unwatched tamil movies", UiState.Content(dev.yourstream.ui.screens.search.SearchResults(null, emptyList(), 0)),
                brain = dev.yourstream.ui.screens.search.BrainAnswer("4 unwatched Tamil movies.", listOf("language Tamil", "unwatched", "movies"), tiles, 4),
            ), {}, {},
        )
    }
}
