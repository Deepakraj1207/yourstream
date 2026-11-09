package dev.yourstream.ui.screens.continuewatching

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.ListRow
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget
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

/** One row: resume or next episode, decided by the domain (WatchIntelligence). */
data class ContinueItem(val itemId: Long, val tile: Tile)

data class ContinueWatchingUiState(val items: UiState<List<ContinueItem>> = UiState.Loading)

sealed interface ContinueWatchingAction {
    /** "Remove from Continue Watching" (until played again). */
    data class Remove(val itemId: Long) : ContinueWatchingAction
    data object Retry : ContinueWatchingAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContinueWatchingViewModel @Inject constructor(private val watch: WatchIntelligence) : ViewModel() {
    private val retry = MutableStateFlow(0)

    val state: StateFlow<ContinueWatchingUiState> = retry.flatMapLatest {
        watch.continueWatching(100)
            .map { list -> ContinueWatchingUiState(list.map { ContinueItem(it.target.itemId, Presentation.tile(it)) }.toUiState(EmptyKind.NOTHING_IN_PROGRESS)) }
            .onStart { emit(ContinueWatchingUiState()) }
            .catch { emit(ContinueWatchingUiState(UiState.Error(it.toUiError()))) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContinueWatchingUiState())

    fun onAction(action: ContinueWatchingAction) {
        when (action) {
            is ContinueWatchingAction.Remove -> viewModelScope.launch { watch.dismiss(action.itemId) }
            ContinueWatchingAction.Retry -> retry.value++
        }
    }
}

@Composable
fun ContinueWatchingRoute(onOpen: (OpenTarget) -> Unit, onBack: (() -> Unit)?, vm: ContinueWatchingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ContinueWatchingScreen(state, vm::onAction, onOpen, onBack)
}

@Composable
fun ContinueWatchingScreen(state: ContinueWatchingUiState, onAction: (ContinueWatchingAction) -> Unit, onOpen: (OpenTarget) -> Unit, onBack: (() -> Unit)? = null) {
    ScreenScaffold(stringResource(R.string.nav_continue), onBack = onBack) {
        StateContent(state.items, onRetry = { onAction(ContinueWatchingAction.Retry) }) { items ->
            LazyColumn(Modifier.testTag("continue")) {
                items(items, key = { it.tile.card.key }) { item ->
                    ListRow(
                        title = item.tile.card.title, supporting = item.tile.card.subtitle, progress = item.tile.card.progress,
                        onClick = { onOpen(item.tile.open) },
                        trailing = {
                            TextButton(onClick = { onAction(ContinueWatchingAction.Remove(item.itemId)) }, modifier = Modifier.focusHighlight()) {
                                Text(stringResource(R.string.action_remove))
                            }
                        },
                    )
                }
            }
        }
    }
}
