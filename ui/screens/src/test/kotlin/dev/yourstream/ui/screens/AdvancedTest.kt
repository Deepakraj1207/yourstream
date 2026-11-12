package dev.yourstream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasTestTag
import dev.yourstream.assistant.core.Entities
import dev.yourstream.assistant.core.Intent
import dev.yourstream.assistant.core.Interpretation
import dev.yourstream.assistant.engine.AssistantItem
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.IndexPhase
import dev.yourstream.media.api.model.IndexProgress
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.ProviderId
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.WatchState
import dev.yourstream.sync.SyncProblem
import dev.yourstream.sync.SyncStatus
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.library.LibraryStatus
import dev.yourstream.ui.screens.library.LibraryStatusScreen
import dev.yourstream.ui.screens.library.MatchAction
import dev.yourstream.ui.screens.library.MatchCandidate
import dev.yourstream.ui.screens.library.MatchEntry
import dev.yourstream.ui.screens.library.MatchScreen
import dev.yourstream.ui.screens.library.MatchUiState
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.search.BrainAnswer
import dev.yourstream.ui.screens.search.MediaBrain
import dev.yourstream.ui.screens.search.SearchResults
import dev.yourstream.ui.screens.search.SearchScreen
import dev.yourstream.ui.screens.search.SearchUiState
import dev.yourstream.ui.screens.stats.StatsScreen
import dev.yourstream.ui.screens.sync.ProgressSyncScreen
import dev.yourstream.ui.screens.sync.SyncAction
import dev.yourstream.ui.screens.sync.SyncUiState
import dev.yourstream.watch.core.DayTotal
import dev.yourstream.watch.core.Share
import dev.yourstream.watch.core.WatchStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pure mappings behind the advanced features. */
class AdvancedMappingTest {
    private fun chat(id: Long, files: Int, state: IndexState = IndexState.COMPLETED, at: Long? = 100, progress: IndexProgress? = null, enabled: Boolean = true, error: String? = null) =
        ChatIndexStatus(id, "Chat $id", enabled, state, files, 0, true, at, null, error, progress)

    @Test
    fun libraryPipelineCountsEachStage() {
        val running = IndexProgress(2, IndexMode.INCREMENTAL, IndexPhase.HEAD, null, 1, 50, 12, 3, 0, -1, 0)
        val s = LibraryStatus.of(
            listOf(chat(1, 1000, at = 500), chat(2, 240, IndexState.SCANNING, progress = running), chat(3, 99, enabled = false), chat(4, 1, IndexState.FAILED, error = "flood wait")),
            mapOf("ACCEPTED" to 180, "MANUAL" to 5, "NEEDS_REVIEW" to 12, "NO_MATCH" to 8, "REJECTED" to 2, "FAILED" to 3),
            titles = 240, movies = 180, series = 60, providers = listOf("TMDB"),
        )
        assertEquals(3, s.sources.size, "disabled chats are not sources")
        assertEquals(1241, s.files)
        assertTrue(s.syncing)
        assertEquals(15, s.sources[1].found)
        assertEquals(500L, s.lastSyncAt)
        assertEquals("flood wait", s.sources[2].error)
        assertEquals(185, s.matched)
        assertEquals(12, s.review)
        assertEquals(10, s.unmatched)
        assertEquals(240 - 185 - 12 - 10 - 3, s.pending)
    }

    private fun interp(intent: Intent = Intent.SEARCH, e: Entities = Entities(), notes: List<String> = emptyList()) = Interpretation("q", intent, e, 0.8, notes = notes)

    @Test
    fun mediaBrainAnswersRequestsNotBareTitles() {
        assertFalse(MediaBrain.isRequest(interp(e = Entities(title = "kaithi"))), "a plain title is a search")
        assertTrue(MediaBrain.isRequest(interp(e = Entities(languages = setOf("ta"), kinds = setOf(ItemKind.MOVIE)))))
        assertTrue(MediaBrain.isRequest(interp(e = Entities(watch = WatchState.UNWATCHED))))
        assertTrue(MediaBrain.isRequest(interp(Intent.RECOMMEND)))
        assertEquals(listOf("language Tamil", "unwatched"), MediaBrain.understood(interp(notes = listOf("tamil → language Tamil", "\"unwatched\" → unwatched"))))
    }

    @Test
    fun brainItemsOpenOneClick() {
        val episode = Presentation.tile(AssistantItem(5, ItemKind.SERIES, "Ben 10", 2005, episodeId = 66, season = 1, episode = 6, reason = "next episode"))
        assertEquals(OpenTarget.Play(PlaybackTarget(5, 66)), episode.open)
        assertEquals(TileCaption.NextEpisode, episode.caption)
        assertEquals("2005 · S01E06", episode.card.subtitle)
        val resume = Presentation.tile(AssistantItem(7, ItemKind.MOVIE, "Kaithi", reason = "continue"))
        assertEquals(OpenTarget.Play(PlaybackTarget(7)), resume.open)
        val pick = Presentation.tile(AssistantItem(8, ItemKind.MOVIE, "Jailer", 2023, reason = "Tamil · action · ★ 7.1"))
        assertIs<OpenTarget.Details>(pick.open)
        assertEquals(TileCaption.Reason("Tamil · action · ★ 7.1"), pick.caption)
    }
}

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp")
class AdvancedScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun set(layout: WindowLayout = WindowLayout.COMPACT, content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { YourStreamTheme(layout) { content() } }

    @Test
    fun libraryStatusShowsEveryStageAndLeadsToReview() {
        var reviews = 0; var syncs = 0
        val s = LibraryStatus(
            sources = listOf(dev.yourstream.ui.screens.library.SourceSync(1, "Tamil Movies", false, 1240, 0, null, null)),
            files = 1240, titles = 240, movies = 180, series = 60, matched = 210, review = 12, unmatched = 18, providers = listOf("TMDB"),
        )
        set { LibraryStatusScreen(s, { syncs++ }, { reviews++ }, {}, {}, now = 60_000) }
        compose.onNodeWithText("240 titles from 1240 files in 1 chat").assertExists()
        compose.onNodeWithText("210 of 240").assertExists()
        compose.onNodeWithTag("sync-now").performClick()
        compose.onNodeWithTag("library-status").performScrollToNode(hasTestTag("review"))
        compose.onNodeWithTag("review").performClick()
        assertEquals(1 to 1, syncs to reviews)
        compose.onNodeWithTag("library-status").performScrollToNode(hasTestTag("privacy-note"))
        compose.onNodeWithText("only a title and year are sent to TMDB", substring = true).assertExists()
    }

    @Test
    fun sourcesShowTheirIndexingStateAndCanBePausedResumedOrStopped() {
        val actions = mutableListOf<dev.yourstream.ui.screens.library.SourceAction>()
        var pauseAll: Boolean? = null
        fun src(id: Long, state: IndexState, paused: Boolean = false, found: Int? = null) =
            dev.yourstream.ui.screens.library.SourceSync(id, "Chat $id", syncing = !paused && state.isPending, files = 10, lastSyncAt = null, error = null,
                found = found, state = state, paused = paused)
        val s = LibraryStatus(
            sources = listOf(src(1, IndexState.SCANNING, found = 340), src(2, IndexState.QUEUED), src(3, IndexState.PAUSED, paused = true), src(4, IndexState.RECOVERING)),
            files = 40, policy = dev.yourstream.media.api.model.IndexingPolicy(wifiOnly = true),
        )
        set { LibraryStatusScreen(s, {}, {}, {}, {}, now = 60_000, onSource = { actions += it }, onPauseAll = { pauseAll = it }) }
        compose.onNodeWithTag("library-status").performScrollToNode(hasTestTag("source:4"))
        compose.onNodeWithTag("source-state:1", useUnmergedTree = true).assertTextEquals("10 files · 340 files found so far")
        compose.onNodeWithTag("source-state:2", useUnmergedTree = true).assertTextEquals("10 files · Waiting for Wi-Fi")
        compose.onNodeWithTag("source-state:3", useUnmergedTree = true).assertTextEquals("10 files · Paused")
        compose.onNodeWithTag("source-state:4", useUnmergedTree = true).assertTextEquals("10 files · Interrupted — continuing soon")
        compose.onNodeWithTag("source-stop:1").performClick()
        compose.onNodeWithTag("source-pause:1").performClick()
        compose.onNodeWithTag("source-pause:3").performClick()
        compose.onNodeWithTag("source-stop:3").assertDoesNotExist()
        assertEquals(
            listOf(
                dev.yourstream.ui.screens.library.SourceAction.Stop(1),
                dev.yourstream.ui.screens.library.SourceAction.Pause(1),
                dev.yourstream.ui.screens.library.SourceAction.Resume(3),
            ),
            actions,
        )
        compose.onNodeWithTag("library-status").performScrollToNode(hasTestTag("pause-all"))
        compose.onNodeWithTag("pause-all").performClick()
        assertEquals(true, pauseAll, "not all paused: the button pauses all")
    }

    @Test
    fun matchReviewConfirmsRejectsAndSearches() {
        val actions = mutableListOf<MatchAction>()
        val ref = MetadataRef(ProviderId("tmdb"), MetadataType.MOVIE, "603692")
        val entry = MatchEntry(42, "Vikram", 2022, listOf(MatchCandidate(ref, "Vikram", 2022, null, null, 71)))
        set { MatchScreen("Check matches", MatchUiState(UiState.Content(listOf(entry))), { actions += it }, {}) }
        compose.onNodeWithText("71% match", substring = true).assertExists()
        compose.onNodeWithTag("candidate:603692").performClick()
        compose.onNodeWithTag("reject:42").performClick()
        compose.onNodeWithTag("search:42").performClick()
        compose.onNodeWithTag("search-title:42").performTextReplacement("Vikram Hitlist")
        compose.onNodeWithTag("search-go:42").performClick()
        assertEquals(listOf(MatchAction.Confirm(42, ref), MatchAction.Reject(42), MatchAction.Search(42, "Vikram Hitlist", 2022)), actions)
    }

    @Test
    fun statsShowTotalsStreakAndLanguages() {
        val today = LocalDate.of(2026, 11, 9)
        val st = WatchStats(
            totalMs = 25 * 3_600_000L, last7DaysMs = 5 * 3_600_000L + 25 * 60_000, previous7DaysMs = 4 * 3_600_000L,
            daily = (13 downTo 0).map { DayTotal(today.minusDays(it.toLong()), if (it % 2 == 0) 3_600_000 else 0) },
            moviesFinished = 6, episodesFinished = 31, titlesWatched = 14, sessions = 52, averageSessionMs = 29 * 60_000,
            currentStreakDays = 3, longestStreakDays = 9, topLanguages = listOf(Share("ta", 1, 0.62f), Share("hi", 1, 0.38f)),
            topGenres = listOf(Share("action", 1, 0.5f)), busiestHour = 21,
        )
        set { StatsScreen(UiState.Content(st), {}) }
        compose.onNodeWithText("5 h 25 min").assertExists()
        compose.onNodeWithText("+35% on the week before").assertExists()
        compose.onNodeWithText("3 days").assertExists()
        compose.onNodeWithText("31 episodes").assertExists()
        compose.onNodeWithTag("stats").performScrollToNode(hasTestTag("stat-languages"))
        compose.onNodeWithText("Tamil").assertExists()
        compose.onNodeWithTag("stats").performScrollToNode(hasTestTag("stats-privacy"))
        compose.onNodeWithText("Nothing is uploaded", substring = true).assertExists()
    }

    @Test
    fun syncIsOffUntilTurnedOnAndExplainsProblems() {
        val actions = mutableListOf<SyncAction>()
        var state by androidx.compose.runtime.mutableStateOf(SyncUiState())
        set { ProgressSyncScreen(state, { actions += it }, {}, now = 120_000) }
        compose.onNodeWithText("Telegram cannot read it", substring = true).assertExists()
        compose.onNodeWithTag("sync-on").assertIsNotEnabled()
        compose.onNodeWithTag("sync-passphrase").performTextInput("short")
        compose.onNodeWithTag("sync-on").assertIsNotEnabled()
        compose.onNodeWithTag("sync-passphrase").performTextInput(" enough")
        compose.onNodeWithTag("sync-on").assertIsEnabled().performClick()
        assertEquals("short enough", String((actions.single() as SyncAction.TurnOn).passphrase))

        state = SyncUiState(SyncStatus(enabled = false, problem = SyncProblem.WRONG_PASSPHRASE))
        compose.onNodeWithTag("sync-problem").assertExists()

        state = SyncUiState(SyncStatus(enabled = true, lastSyncAt = 0, lastFrom = "Pixel 8", lastPulled = 3, shared = 120))
        compose.onNodeWithText("with progress from Pixel 8", substring = true).assertExists()
        compose.onNodeWithText("3 updated from other devices · 120 shared in total").assertExists()
        compose.onNodeWithTag("sync-now").performClick()
        compose.onNodeWithTag("sync-off").performClick()
        compose.onNodeWithTag("sync-off-keep").performClick()
        assertEquals(listOf(SyncAction.SyncNow, SyncAction.TurnOff(deleteShared = false)), actions.drop(1))
    }

    @Test
    fun searchShowsTheBrainAnswerWithOneClickPlay() {
        val opened = mutableListOf<OpenTarget>()
        val play = OpenTarget.Play(PlaybackTarget(5, 13))
        val tile = Tile(dev.yourstream.ui.core.MediaCardModel("brain:5:13", "Ben 10", "S02E01"), play, itemId = 5)
        val state = SearchUiState(
            "play ben 10 season 2", UiState.Content(SearchResults(null, emptyList(), 0)),
            brain = BrainAnswer("Next: Ben 10 S02E01.", listOf("season 2"), listOf(tile), 1, play),
        )
        set { SearchScreen(state, {}, { opened += it }) }
        compose.onNodeWithTag("brain-message").assertExists()
        compose.onNodeWithText("season 2").assertExists()
        compose.onNodeWithTag("brain-play").performClick()
        assertEquals(listOf<OpenTarget>(play), opened)
    }
}
