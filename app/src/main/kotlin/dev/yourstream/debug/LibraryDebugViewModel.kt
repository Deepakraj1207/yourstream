package dev.yourstream.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.ContinueWatchingEntry
import dev.yourstream.library.api.Episode
import dev.yourstream.library.api.EpisodeRepository
import dev.yourstream.library.api.LibraryItemDetails
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.LibrarySort
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.WatchFilter
import dev.yourstream.library.usecase.GetContinueWatching
import dev.yourstream.library.usecase.GetEpisodes
import dev.yourstream.library.usecase.GetItemDetails
import dev.yourstream.library.usecase.MarkAsWatched
import dev.yourstream.library.usecase.MergeMedia
import dev.yourstream.library.usecase.SearchMedia
import dev.yourstream.library.usecase.SyncLibrary
import dev.yourstream.library.usecase.ToggleFavorite
import dev.yourstream.library.usecase.UpdateWatchProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Stage-1 debug state for the library. Uses the library use cases only. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryDebugViewModel @Inject constructor(
    private val search: SearchMedia,
    private val getDetails: GetItemDetails,
    private val getEpisodes: GetEpisodes,
    private val toggleFavorite: ToggleFavorite,
    private val markAsWatched: MarkAsWatched,
    private val updateProgress: UpdateWatchProgress,
    private val merge: MergeMedia,
    private val syncLibrary: SyncLibrary,
    private val media: MediaRepository,
    private val episodesRepo: EpisodeRepository,
    continueWatching: GetContinueWatching,
) : ViewModel() {

    private val _query = MutableStateFlow(LibraryQuery())
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()

    val items: Flow<PagingData<LibraryItemSummary>> = _query.debounce(200).flatMapLatest { search(it) }.cachedIn(viewModelScope)
    val count: StateFlow<Int> = _query.debounce(200).flatMapLatest { search.count(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val continueWatching: StateFlow<List<ContinueWatchingEntry>> = continueWatching(10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _details = MutableStateFlow<LibraryItemDetails?>(null)
    val details: StateFlow<LibraryItemDetails?> = _details.asStateFlow()

    private val _seasonId = MutableStateFlow<Long?>(null)
    val seasonId: StateFlow<Long?> = _seasonId.asStateFlow()

    val episodes: Flow<PagingData<Episode>> = _details.flatMapLatest { d ->
        if (d == null || d.summary.kind != LibraryKind.SERIES) emptyFlow() else _seasonId.flatMapLatest { getEpisodes(d.summary.id, it) }
    }.cachedIn(viewModelScope)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setText(t: String) = update { it.copy(text = t) }
    fun setKind(kind: LibraryKind?) = update { it.copy(kinds = setOfNotNull(kind)) }
    fun cycleSort() = update { it.copy(sort = LibrarySort.entries[(it.sort.ordinal + 1) % LibrarySort.entries.size]) }
    fun toggleReversed() = update { it.copy(reversed = !it.reversed) }
    fun cycleWatch() = update { it.copy(watch = WatchFilter.entries[(it.watch.ordinal + 1) % WatchFilter.entries.size]) }
    fun toggleFavoritesOnly() = update { it.copy(favoritesOnly = !it.favoritesOnly) }
    fun toggleUnavailable() = update { it.copy(includeUnavailable = !it.includeUnavailable) }
    fun setLanguage(code: String?) = update { it.copy(language = code) }
    private fun update(change: (LibraryQuery) -> LibraryQuery) { _query.value = change(_query.value) }

    fun open(itemId: Long) = viewModelScope.launch { _seasonId.value = null; _details.value = getDetails(itemId) }
    fun close() { _details.value = null }
    fun selectSeason(id: Long?) { _seasonId.value = id }
    private fun refresh() = _details.value?.let { open(it.summary.id) }

    fun favorite(itemId: Long, value: Boolean) = viewModelScope.launch { toggleFavorite(itemId, value); refresh() }
    fun markWatched(target: PlaybackTarget, value: Boolean) = viewModelScope.launch { markAsWatched(target, value); refresh() }

    /** Simulates playback (no player yet): advances the unit by 20 % of a 45 min duration. */
    fun simulatePlayback(target: PlaybackTarget, current: Long?) = viewModelScope.launch {
        val duration = 45 * 60_000L
        val versions = if (target.episodeId != null) episodesRepo.versions(target.episodeId!!) else media.versions(target)
        val progress = updateProgress(target, versions.firstOrNull()?.mediaId, (current ?: 0) + duration / 5, duration)
        _message.value = "Progress ${(progress.fraction * 100).toInt()}%${if (progress.completed) " — completed" else ""}"
        refresh()
    }

    fun mergeInto(targetId: Long) = viewModelScope.launch {
        val source = _details.value?.summary?.id ?: return@launch
        _message.value = when (val r = merge(targetId, source)) {
            is AppResult.Ok -> "Merged #$source into #$targetId"
            is AppResult.Err -> r.error.message
        }
        open(targetId)
    }

    fun syncNow() = viewModelScope.launch { _message.value = "Sync: ${syncLibrary()}" }
    fun dismissMessage() { _message.value = null }
}
