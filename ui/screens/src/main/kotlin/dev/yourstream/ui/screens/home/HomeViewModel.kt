package dev.yourstream.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.MovieRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.SeriesRepository
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.ArtworkLookup
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.TileActionHandler
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.watch.engine.ContinueEntry
import dev.yourstream.watch.engine.WatchIntelligence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home from the domain: Continue Watching and the hero from [WatchIntelligence] (its decisions),
 * the poster rows from the library repositories (paged, with artwork), the library size from
 * [MediaRepository.count], and the status banner from Telegram/indexing state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val watch: WatchIntelligence,
    private val media: MediaRepository,
    movies: MovieRepository,
    series: SeriesRepository,
    private val index: MediaIndexRepository,
    private val auth: TelegramAuthManager,
    private val telegram: TelegramManager,
    private val artwork: ArtworkLookup,
    private val actions: TileActionHandler,
) : ViewModel() {
    private val retry = MutableStateFlow(0)

    private fun paged(source: () -> Flow<PagingData<dev.yourstream.library.api.LibraryItemSummary>>): Flow<PagingData<Tile>> =
        source().map { p -> p.map(Presentation::tile) }.cachedIn(viewModelScope)

    val recentlyAdded: Flow<PagingData<Tile>> = paged { media.recentlyAdded(pageSize = 20) }
    val movies: Flow<PagingData<Tile>> = paged { movies.movies(LibraryQuery(sort = LibrarySort.RECENTLY_ADDED, watch = WatchFilter.UNWATCHED), 20) }
    val series: Flow<PagingData<Tile>> = paged { series.series(LibraryQuery(sort = LibrarySort.RECENTLY_WATCHED), 20) }
    val favorites: Flow<PagingData<Tile>> = paged { media.favorites(20) }

    private val continueWatching: Flow<UiState<List<Tile>>> = retry.flatMapLatest {
        watch.continueWatching(20)
            .map { list -> list.map { Presentation.tile(it, artwork.poster(it.target.itemId)) }.toUiState(EmptyKind.NOTHING_IN_PROGRESS) }
            .onStart { emit(UiState.Loading) }
            .catch { emit(UiState.Error(it.toUiError())) }
    }

    /** Continue the most recent thing; else feature the newest arrival. */
    private val hero: Flow<UiState<HeroItem>> = combine(watch.continueWatching(1), watch.recentlyAdded(1)) { cw, added ->
        cw.firstOrNull()?.let { heroOf(it, artwork.poster(it.target.itemId)) }
            ?: added.firstOrNull()?.let { a ->
                HeroItem(HeroItem.Kind.NEW, "hero:${a.itemId}", a.title, a.year?.toString(), artwork.poster(a.itemId), null,
                    primary = if (a.kind == LibraryKind.SERIES) OpenTarget.Details(a.itemId) else OpenTarget.Play(PlaybackTarget(a.itemId)),
                    secondary = OpenTarget.Details(a.itemId))
            }
    }.map { it?.let { h -> UiState.Content(h) } ?: UiState.Empty(EmptyKind.LIBRARY_EMPTY) }
        .onStart { emit(UiState.Loading) }
        .catch { emit(UiState.Error(it.toUiError())) }

    private val banner: Flow<HomeBanner?> = combine(auth.state, index.observeChats(), telegram.connectionState) { a, chats, conn ->
        when {
            a !is AuthState.Ready && a !is AuthState.Unknown && a !is AuthState.WaitParameters -> HomeBanner.NOT_SIGNED_IN
            chats.none { it.enabled } -> HomeBanner.NO_SOURCES
            conn == TelegramConnectionState.WAITING_FOR_NETWORK -> HomeBanner.OFFLINE
            chats.any { it.enabled && it.progress != null } -> HomeBanner.INDEXING
            else -> null
        }
    }.catch { emit(null) }

    private val count: Flow<Int?> = media.count(LibraryQuery()).map<Int, Int?> { it }.catch { emit(null) }

    val state: StateFlow<HomeUiState> = combine(banner, hero, continueWatching, count) { b, h, cw, n -> HomeUiState(b, h, cw, n) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Retry -> retry.value++
            is HomeAction.Tile -> viewModelScope.launch { actions.run(action.action) }
        }
    }

    companion object {
        fun heroOf(e: ContinueEntry, poster: String?) = HeroItem(
            HeroItem.Kind.CONTINUE, "hero:${e.target.itemId}:${e.target.episodeKey}", e.title,
            listOfNotNull(e.year?.toString(), e.episode?.title).joinToString(" · ").ifEmpty { null },
            poster, (e.percent / 100f).takeIf { e.percent > 0 },
            primary = OpenTarget.Play(e.target, e.lastMediaId), secondary = OpenTarget.Details(e.target.itemId),
            episodeLabel = e.episode?.label,
        )
    }
}
