package dev.yourstream.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.Tile
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
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val watch: WatchIntelligence,
    private val index: MediaIndexRepository,
    private val auth: TelegramAuthManager,
    private val telegram: TelegramManager,
) : ViewModel() {
    private val retry = MutableStateFlow(0)

    private fun <T> row(source: () -> Flow<List<T>>, map: (T) -> Tile, empty: EmptyKind): Flow<UiState<List<Tile>>> =
        retry.flatMapLatest {
            source().map { list -> list.map(map).toUiState(empty) }
                .onStart { emit(UiState.Loading) }
                .catch { emit(UiState.Error(it.toUiError())) }
        }

    private val banner: Flow<HomeBanner?> = combine(auth.state, index.observeChats(), telegram.connectionState) { a, chats, conn ->
        when {
            a !is AuthState.Ready && a !is AuthState.Unknown && a !is AuthState.WaitParameters -> HomeBanner.NOT_SIGNED_IN
            chats.none { it.enabled } -> HomeBanner.NO_SOURCES
            conn == TelegramConnectionState.WAITING_FOR_NETWORK -> HomeBanner.OFFLINE
            chats.any { it.enabled && it.progress != null } -> HomeBanner.INDEXING
            else -> null
        }
    }.catch { emit(null) }

    val state: StateFlow<HomeUiState> = combine(
        banner,
        row({ watch.continueWatching(20) }, Presentation::tile, EmptyKind.NOTHING_IN_PROGRESS),
        row({ watch.recentlyAdded(30) }, Presentation::tile, EmptyKind.LIBRARY_EMPTY),
        row({ watch.favorites() }, Presentation::tile, EmptyKind.NO_FAVORITES),
    ) { b, cw, added, fav -> HomeUiState(b, cw, added, fav) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Retry -> retry.value++
        }
    }
}
