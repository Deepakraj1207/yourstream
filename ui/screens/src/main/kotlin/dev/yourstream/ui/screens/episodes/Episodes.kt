package dev.yourstream.ui.screens.episodes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.Season
import dev.yourstream.library.api.SeriesRepository
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.navigation.EpisodesRoute
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.engine.WatchIntelligence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeasonTab(val seasonId: Long, val name: String)

data class EpisodeItem(
    val episodeId: Long,
    val title: String,
    val progress: Float?,
    val watched: Boolean,
    val available: Boolean,
    val play: OpenTarget.Play,
)

data class EpisodesUiState(
    val seasons: UiState<List<SeasonTab>> = UiState.Loading,
    val selectedSeasonId: Long? = null,
    /** "Next: S01E06" from the domain's next-episode decision. */
    val nextUp: Pair<String, OpenTarget.Play>? = null,
)

sealed interface EpisodesAction {
    data class SelectSeason(val seasonId: Long) : EpisodesAction
    data class SetWatched(val episodeId: Long, val watched: Boolean) : EpisodesAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EpisodesViewModel @Inject constructor(
    saved: SavedStateHandle,
    series: SeriesRepository,
    private val episodes: EpisodeRepository,
    private val history: WatchHistoryRepository,
    private val watch: WatchIntelligence,
) : ViewModel() {
    private val route = saved.toRoute<EpisodesRoute>()
    private val selected = MutableStateFlow(route.seasonId)
    private val nextUp = MutableStateFlow<Pair<String, OpenTarget.Play>?>(null)

    private val seasons: Flow<UiState<List<SeasonTab>>> = series.seasons(route.seriesId)
        .map { list -> list.map { SeasonTab(it.id, it.displayName) }.toUiState(EmptyKind.NO_EPISODES) }
        .catch { emit(UiState.Error(it.toUiError())) }

    val state: StateFlow<EpisodesUiState> = combine(seasons, selected, nextUp) { s, sel, next ->
        val tabs = (s as? UiState.Content)?.value.orEmpty()
        EpisodesUiState(s, sel ?: tabs.firstOrNull()?.seasonId, next)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodesUiState())

    val items: Flow<PagingData<EpisodeItem>> = state.map { it.selectedSeasonId }.filterNotNull()
        .flatMapLatest { seasonId -> episodes.episodes(route.seriesId, seasonId) }
        .map { page -> page.map(::item) }
        .cachedIn(viewModelScope)

    init { refreshNextUp() }

    fun onAction(action: EpisodesAction) {
        when (action) {
            is EpisodesAction.SelectSeason -> selected.value = action.seasonId
            is EpisodesAction.SetWatched -> viewModelScope.launch {
                history.markWatched(dev.yourstream.library.api.PlaybackTarget(route.seriesId, action.episodeId), action.watched)
                refreshNextUp()
            }
        }
    }

    private fun refreshNextUp() = viewModelScope.launch {
        nextUp.value = runCatching { watch.nextEpisode(route.seriesId) }.getOrNull()
            ?.takeIf { it.action != NextAction.NONE }?.episode
            ?.let { e -> e.label to OpenTarget.Play(dev.yourstream.library.api.PlaybackTarget(route.seriesId, e.episodeId), e.lastMediaId) }
    }

    companion object {
        fun item(e: Episode) = EpisodeItem(
            episodeId = e.id, title = Presentation.episodeTitle(e),
            progress = e.progress?.takeIf { !it.completed && it.positionMs > 0 }?.fraction,
            watched = e.progress?.completed == true, available = e.availableCount > 0, play = OpenTarget.Play(e.target),
        )
    }
}
