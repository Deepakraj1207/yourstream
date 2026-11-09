package dev.yourstream.ui.screens.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.Tile
import dev.yourstream.watch.engine.WatchIntelligence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Favorite titles with their watch status (domain: WatchIntelligence.favorites). */
data class FavoritesUiState(val items: UiState<List<FavoriteItem>> = UiState.Loading)

data class FavoriteItem(val itemId: Long, val tile: Tile)

sealed interface FavoritesAction {
    data class Remove(val itemId: Long) : FavoritesAction
    data object Retry : FavoritesAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val watch: WatchIntelligence,
    private val media: MediaRepository,
) : ViewModel() {
    private val retry = MutableStateFlow(0)

    val state: StateFlow<FavoritesUiState> = retry.flatMapLatest {
        watch.favorites()
            .map { list -> FavoritesUiState(list.map { FavoriteItem(it.itemId, Presentation.tile(it)) }.toUiState(EmptyKind.NO_FAVORITES)) }
            .onStart { emit(FavoritesUiState(UiState.Loading)) }
            .catch { emit(FavoritesUiState(UiState.Error(it.toUiError()))) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    fun onAction(action: FavoritesAction) {
        when (action) {
            is FavoritesAction.Remove -> viewModelScope.launch { media.setFavorite(action.itemId, false) }
            FavoritesAction.Retry -> retry.value++
        }
    }
}
