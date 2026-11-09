package dev.yourstream.ui.screens.continuewatching

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.ui.core.CardStyle
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.MediaGrid
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.SkeletonGrid
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.ArtworkLookup
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.TileAction
import dev.yourstream.ui.screens.TileActionHandler
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.tileActions
import dev.yourstream.ui.screens.tileCard
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

/** Everything to resume or start next, in the domain's order (most recent activity first). */
data class ContinueWatchingUiState(val items: UiState<List<Tile>> = UiState.Loading)

sealed interface ContinueWatchingAction {
    data class Tile(val action: TileAction) : ContinueWatchingAction
    data object Retry : ContinueWatchingAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContinueWatchingViewModel @Inject constructor(
    private val watch: WatchIntelligence,
    private val artwork: ArtworkLookup,
    private val actions: TileActionHandler,
) : ViewModel() {
    private val retry = MutableStateFlow(0)

    val state: StateFlow<ContinueWatchingUiState> = retry.flatMapLatest {
        watch.continueWatching(100)
            .map { list -> ContinueWatchingUiState(list.map { Presentation.tile(it, artwork.poster(it.target.itemId)) }.toUiState(EmptyKind.NOTHING_IN_PROGRESS)) }
            .onStart { emit(ContinueWatchingUiState()) }
            .catch { emit(ContinueWatchingUiState(UiState.Error(it.toUiError()))) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContinueWatchingUiState())

    fun onAction(action: ContinueWatchingAction) {
        when (action) {
            is ContinueWatchingAction.Tile -> viewModelScope.launch { actions.run(action.action) }
            ContinueWatchingAction.Retry -> retry.value++
        }
    }
}

@Composable
fun ContinueWatchingRoute(onOpen: (OpenTarget) -> Unit, vm: ContinueWatchingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ContinueWatchingScreen(state, vm::onAction, onOpen)
}

@Composable
fun ContinueWatchingScreen(state: ContinueWatchingUiState, onAction: (ContinueWatchingAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    val count = (state.items as? UiState.Content)?.value?.size?.let { pluralStringResource(R.plurals.titles_count, it, it) }
    val actions = tileActions({ onAction(ContinueWatchingAction.Tile(it)) }, onOpen)
    val card = tileCard()
    ScreenScaffold(stringResource(R.string.nav_continue), count = count) {
        StateContent(state.items, onRetry = { onAction(ContinueWatchingAction.Retry) }, loading = { SkeletonGrid(CardStyle.WIDE) }) { tiles ->
            MediaGrid(tiles, card, { onOpen(it.open) }, style = CardStyle.WIDE, actions = actions)
        }
    }
}
