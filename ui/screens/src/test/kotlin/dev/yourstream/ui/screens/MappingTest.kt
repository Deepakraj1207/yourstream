package dev.yourstream.ui.screens

import dev.yourstream.library.api.LibraryItemDetails
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.MediaVersion
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.library.api.WatchProgress
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.playback.media3.PlaybackError
import dev.yourstream.playback.media3.PlaybackErrorKind
import dev.yourstream.playback.media3.PlaybackState
import dev.yourstream.playback.media3.PlaybackStatus
import dev.yourstream.playback.media3.TrackState
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchHit
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListState
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.CodeDeliveryType
import dev.yourstream.telegram.api.model.CodeInfo
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.ErrorKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.screens.browse.BrowseFilters
import dev.yourstream.ui.screens.details.DetailsViewModel
import dev.yourstream.ui.screens.details.PrimaryAction
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.player.PlayerViewModel
import dev.yourstream.ui.screens.settings.AccountStep
import dev.yourstream.ui.screens.settings.AccountViewModel
import dev.yourstream.ui.screens.settings.SourcesViewModel
import dev.yourstream.watch.core.EpisodeProgress
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.core.NextEpisodeDecision
import dev.yourstream.watch.core.NextReason
import dev.yourstream.watch.core.WatchStatus
import dev.yourstream.watch.engine.WatchSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

fun summary(id: Long, kind: LibraryKind = LibraryKind.MOVIE, title: String = "Vikram", available: Int = 1, episodes: Int = 0, watched: Int = 0, progress: WatchProgress? = null) =
    LibraryItemSummary(id, kind, title, 2022, available, 0, episodes, watched, 1080, 0, 0, 0, null, false, progress)

fun watchSummary(id: Long, kind: LibraryKind = LibraryKind.MOVIE, position: Long = 0, next: NextEpisodeDecision? = null, percent: Int = 0) =
    WatchSummary(id, kind, "x", null, WatchStatus.IN_PROGRESS, percent, position, 0, null, 0, 0, 0, 0, 0, false, null, next)

class MappingTest {
    @Test
    fun presentationTiles() {
        val series = Presentation.tile(summary(1, LibraryKind.SERIES, "Ben 10", episodes = 10, watched = 4))
        assertEquals("item:1", series.card.key)
        assertEquals(0.4f, series.card.progress)
        assertEquals("2022 · 10 ep", series.card.subtitle)
        assertEquals(OpenTarget.Details(1), series.open)
        val movie = Presentation.tile(summary(2, progress = WatchProgress(600, 1000, false, 1)))
        assertEquals(0.6f, movie.card.progress)
        assertEquals("1080p", movie.card.badge)
        val done = Presentation.tile(summary(3, progress = WatchProgress(0, 1000, true, 1)))
        assertNull(done.card.progress, "finished: no bar")
        assertEquals(true, done.card.watched)
        assertEquals(true, done.watched, "offers \"mark unwatched\"")
        assertEquals(false, series.isFavorite)

        val episodeHit = Presentation.tile(SearchHit(5, ItemKind.SERIES, "Ben 10", null, 1.0, emptyList(), emptyList(), season = 2, episodeId = 55, episodeNumber = 5))
        assertEquals(OpenTarget.Play(PlaybackTarget(5, 55)), episodeHit.open, "s2e5 opens the episode")
        assertEquals("S02E05", episodeHit.card.subtitle)
    }

    @Test
    fun detailsPrimaryActionComesFromTheDomain() {
        fun details(s: LibraryItemSummary) = LibraryItemDetails(s, "", false, false, listOf("ta"),
            listOf(MediaVersion(9, 1, 1, "Vikram.1080p.mkv", 2_000_000_000, null, 1080, "WEB-DL", null, listOf("ta"), false, false, null, false, false, "NOT_DOWNLOADED")), emptyList())
        val resume = DetailsViewModel.content(details(summary(1)), watchSummary(1, position = 1_200_000))
        assertEquals(PrimaryAction.Resume(OpenTarget.Play(PlaybackTarget(1)), 1_200_000), resume.primary)
        assertEquals(listOf("Tamil"), resume.languages)
        assertEquals("1080p · WEB-DL · 2.0 GB · ta", resume.versions.single().detail)
        assertIs<PrimaryAction.Play>(DetailsViewModel.content(details(summary(1)), null).primary)
        assertNull(DetailsViewModel.content(details(summary(1, available = 0)), null).primary, "nothing to play")

        val ep = EpisodeProgress(66, 1, 6, positionMs = 10, lastMediaId = 7)
        val next = NextEpisodeDecision(NextAction.RESUME, NextReason.IN_PROGRESS, ep, ep)
        val series = DetailsViewModel.content(details(summary(2, LibraryKind.SERIES)), watchSummary(2, LibraryKind.SERIES, next = next))
        assertEquals(PrimaryAction.Episode(OpenTarget.Play(PlaybackTarget(2, 66), 7), "S01E06", resume = true), series.primary)
        val finished = NextEpisodeDecision(NextAction.NONE, NextReason.SERIES_FINISHED, null, ep)
        assertNull(DetailsViewModel.content(details(summary(2, LibraryKind.SERIES)), watchSummary(2, LibraryKind.SERIES, next = finished)).primary)
    }

    @Test
    fun playerStateMapping() {
        val s = PlayerViewModel.map(
            PlaybackState(status = PlaybackStatus.ERROR, error = PlaybackError(PlaybackErrorKind.NETWORK, "timeout", retryable = true), recovery = "retry 1 in 2 s"),
            TrackState(), null, null,
        )
        assertEquals(ErrorKind.OFFLINE, s.error!!.kind)
        assertEquals("retry 1 in 2 s", s.recovery)
        assertEquals(PlaybackStatus.BUFFERING, PlayerViewModel.map(PlaybackState(status = PlaybackStatus.BUFFERING), TrackState(), null, null).status)
        assertEquals(true, PlayerViewModel.map(PlaybackState(status = PlaybackStatus.PREPARING), TrackState(), null, null).loading)
    }

    @Test
    fun accountStepsMirrorAuthState() {
        assertEquals(AccountStep.Phone, AccountViewModel.step(AuthState.WaitPhoneNumber, null))
        assertEquals(AccountStep.Code("+49***67", 5), AccountViewModel.step(AuthState.WaitCode(CodeInfo("+49***67", CodeDeliveryType.TELEGRAM_MESSAGE, null, 60, 5)), null))
        assertEquals(AccountStep.Password("cat"), AccountViewModel.step(AuthState.WaitPassword("cat", false, ""), null))
        assertEquals(AccountStep.Qr("tg://login?token=x"), AccountViewModel.step(AuthState.WaitOtherDeviceConfirmation("tg://login?token=x"), null))
        assertEquals(AccountStep.SignedIn("Asha"), AccountViewModel.step(AuthState.Ready, "Asha"))
        assertEquals(AccountStep.NotConfigured("no api id"), AccountViewModel.step(AuthState.NotConfigured("no api id"), null))
    }

    @Test
    fun sourceRows() {
        fun chat(id: Long, title: String) = TelegramChat(id, title, ChatKind.BasicGroup(id), emptyList(), 0, null, false, null)
        fun status(id: Long, enabled: Boolean, title: String = "c$id") = ChatIndexStatus(id, title, enabled, IndexState.IDLE, 12, 0, true, null, null, null, null)
        val list = ChatListState(ChatListType.Main, listOf(chat(1, "Friends"), chat(2, "Tamil Movies")), isLoading = false, hasMore = true, error = null)
        val rows = (SourcesViewModel.rows(list, listOf(status(2, true), status(9, true, "Old channel"))) as UiState.Content).value
        assertEquals(listOf(2L, 9L, 1L), rows.map { it.chatId }, "selected first, including ones Telegram did not list yet")
        assertEquals(12, rows.first().mediaCount)
        assertEquals(UiState.Loading, SourcesViewModel.rows(list.copy(chats = emptyList(), isLoading = true), emptyList()))
        assertEquals(UiState.Empty(EmptyKind.NO_CHATS), SourcesViewModel.rows(list.copy(chats = emptyList()), emptyList()))
        assertIs<UiState.Error>(SourcesViewModel.rows(list.copy(chats = emptyList(), error = dev.yourstream.core.common.AppError.Network()), emptyList()))
    }

    @Test
    fun browseFiltersMapToTheDomainQuery() {
        val f = BrowseFilters(kind = LibraryKind.SERIES, sort = LibrarySort.TITLE, watch = WatchFilter.UNWATCHED)
        assertEquals(setOf(LibraryKind.SERIES), f.toQuery(null).kinds)
        assertEquals(setOf(LibraryKind.MOVIE), f.toQuery(LibraryKind.MOVIE).kinds, "Movies screen is always movies")
        assertEquals(WatchFilter.UNWATCHED, f.toQuery(null).watch)
        assertEquals(true, f.isFiltered)
    }
}
