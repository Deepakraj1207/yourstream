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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.yourstream.ui.screens.IndexingStatus
import dev.yourstream.ui.screens.IndexingMonitor
import kotlinx.coroutines.flow.debounce
import dev.yourstream.ui.screens.HomeCatalogs
import javax.inject.Inject

/**
 * Home from the domain: Continue Watching and the hero from [WatchIntelligence] (its decisions),
 * the poster rows from the library repositories (paged, with artwork), the library size from
 * [MediaRepository.count], and the status banner from Telegram/indexing state.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
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
    private val assistant: dev.yourstream.assistant.engine.MediaAssistant,
    private val reviewQueue: dev.yourstream.ui.screens.ReviewQueue,
    private val indexingMonitor: IndexingMonitor,
    private val homeCatalogs: HomeCatalogs,
) : ViewModel() {
    private val retry = MutableStateFlow(0)

    // Live: new files appear in the rows while chats are indexed.
    private fun paged(source: () -> Flow<PagingData<dev.yourstream.library.api.LibraryItemSummary>>): Flow<PagingData<Tile>> =
        source().map { p -> p.map(Presentation::tile) }.cachedIn(viewModelScope)

    val recentlyAdded: Flow<PagingData<Tile>> = paged { media.recentlyAdded(pageSize = 20) }
    val series: Flow<PagingData<Tile>> = paged { series.series(LibraryQuery(sort = LibrarySort.RECENTLY_WATCHED), 20) }

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
            else -> null
        }
    }.catch { emit(null) }

    private val count: Flow<Int?> = media.count(LibraryQuery()).map<Int, Int?> { it }.catch { emit(null) }


    /** Rows from the user's catalog add-ons (Settings → Add-ons); reloaded when they change. */
    private val catalogs: Flow<List<CatalogRow>> = homeCatalogs.rows().onStart { emit(emptyList()) }.catch { emit(emptyList()) }

    /**
     * Recommendations from the local watch profile (nothing leaves the device), recomputed when the
     * viewing history or the library changes.
     */
    private val forYou: Flow<UiState<ForYou>> = combine(watch.recentlyWatched(1), count.debounce(RECOMMEND_DEBOUNCE_MS)) { recent, n -> recent.firstOrNull()?.lastWatchedAt to n }
        .distinctUntilChanged()
        .mapLatest {
            val r = assistant.recommendations(FOR_YOU)
            val tiles = r.items.mapIndexed { i, a -> Presentation.tile(a, if (i < 12) artwork.poster(a.itemId) else null) }
            if (tiles.isEmpty()) UiState.Empty(EmptyKind.GENERIC) else UiState.Content(ForYou(r.basis, tiles))
        }
        .onStart { emit(UiState.Loading) }
        .catch { emit(UiState.Error(it.toUiError())) }

    private val review: Flow<Int> = reviewQueue.count()

    // Indexing progress is shown by the shell's pill; Home does not re-render on every page read.
    private val extras = combine(forYou, review, catalogs) { f, r, c -> Extras(f, r, c) }

    private data class Extras(val forYou: UiState<ForYou>, val review: Int, val catalogs: List<CatalogRow>)

    val state: StateFlow<HomeUiState> = combine(banner, hero, continueWatching, count, extras) { b, h, cw, n, e ->
        // Lowest-priority banner: only when nothing more urgent is shown.
        HomeUiState(b ?: HomeBanner.REVIEW.takeIf { e.review > 0 }, h, cw, n, e.forYou, e.review, IndexingStatus(), e.catalogs)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Retry -> retry.value++
            is HomeAction.Tile -> viewModelScope.launch { actions.run(action.action) }
        }
    }

    companion object {
        const val FOR_YOU = 20
        /** While indexing adds titles every moment, recommendations are recomputed at most this often. */
        /** Recommendations wait for the library to be quiet (not recomputed every sync while indexing). */
        const val RECOMMEND_DEBOUNCE_MS = 30_000L

        fun heroOf(e: ContinueEntry, poster: String?) = HeroItem(
            HeroItem.Kind.CONTINUE, "hero:${e.target.itemId}:${e.target.episodeKey}", e.title,
            listOfNotNull(e.year?.toString(), e.episode?.title).joinToString(" · ").ifEmpty { null },
            poster, (e.percent / 100f).takeIf { e.percent > 0 },
            primary = OpenTarget.Play(e.target, e.lastMediaId), secondary = OpenTarget.Details(e.target.itemId),
            episodeLabel = e.episode?.label,
        )
    }
}
