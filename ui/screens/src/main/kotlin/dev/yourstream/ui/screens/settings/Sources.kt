package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.MediaIndexer
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.telegram.api.model.ChatListState
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.ListRow
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A Telegram chat as a possible media source, with its indexing status (from the indexer). */
data class SourceRow(val chatId: Long, val title: String, val enabled: Boolean, val mediaCount: Int?, val indexing: Boolean, val error: String?)

data class SourcesUiState(val chats: UiState<List<SourceRow>> = UiState.Loading, val hasMore: Boolean = false, val actionError: UiError? = null)

sealed interface SourcesAction {
    data class Toggle(val chatId: Long, val enabled: Boolean) : SourcesAction
    data class Rescan(val chatId: Long) : SourcesAction
    data object LoadMore : SourcesAction
}

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val chats: TelegramChatRepository,
    private val indexer: MediaIndexer,
    index: MediaIndexRepository,
) : ViewModel() {
    private val actionError = MutableStateFlow<UiError?>(null)

    val state: StateFlow<SourcesUiState> = combine(chats.chatList(ChatListType.Main), index.observeChats(), actionError) { list, statuses, err ->
        SourcesUiState(rows(list, statuses), list.hasMore, err)
    }.catch { emit(SourcesUiState(UiState.Error(it.toUiError()))) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUiState())

    init { viewModelScope.launch { chats.loadMore(ChatListType.Main) } }

    fun onAction(action: SourcesAction) {
        viewModelScope.launch {
            actionError.value = null
            when (action) {
                // Enabling queues indexing in the background (the indexer decides how).
                is SourcesAction.Toggle -> if (action.enabled) {
                    (indexer.enableChat(action.chatId) as? AppResult.Err)?.let { actionError.value = it.error.toUiError() }
                        ?: indexer.schedule(action.chatId)
                } else indexer.disableChat(action.chatId, purge = false)
                is SourcesAction.Rescan -> indexer.schedule(action.chatId, IndexMode.FULL_RESCAN)
                SourcesAction.LoadMore -> (chats.loadMore(ChatListType.Main) as? AppResult.Err)?.let { actionError.value = it.error.toUiError() }
            }
        }
    }

    companion object {
        /** Selected sources first, then the account's chats in Telegram's order. */
        fun rows(list: ChatListState, statuses: List<ChatIndexStatus>): UiState<List<SourceRow>> {
            val byId = statuses.associateBy { it.chatId }
            if (list.chats.isEmpty()) {
                list.error?.let { return UiState.Error(it.toUiError()) }
                if (list.isLoading) return UiState.Loading
            }
            val fromTelegram = list.chats.map { c ->
                val s = byId[c.id]
                SourceRow(c.id, c.title, s?.enabled == true, s?.mediaCount, s?.progress != null, s?.lastError)
            }
            // Selected sources Telegram did not list (yet): keep them visible and switchable.
            val extra = statuses.filter { s -> s.enabled && list.chats.none { it.id == s.chatId } }
                .map { SourceRow(it.chatId, it.title, true, it.mediaCount, it.progress != null, it.lastError) }
            val rows = (fromTelegram + extra).sortedByDescending { it.enabled }
            return if (rows.isEmpty()) UiState.Empty(EmptyKind.NO_CHATS) else UiState.Content(rows)
        }
    }
}

@Composable
fun SourcesRoute(onBack: () -> Unit, vm: SourcesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SourcesScreen(state, vm::onAction, onBack)
}

@Composable
fun SourcesScreen(state: SourcesUiState, onAction: (SourcesAction) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.settings_sources), onBack = onBack) {
        StateContent(state.chats) { rows ->
            LazyColumn(Modifier.testTag("sources")) {
                state.actionError?.let { e -> item { Text(e.detail ?: stringResource(R.string.ui_error_generic), modifier = Modifier.testTag("sources-error")) } }
                items(rows, key = { it.chatId }) { r ->
                    val status = when {
                        r.error != null -> r.error
                        r.indexing -> stringResource(R.string.sources_indexing)
                        r.enabled && r.mediaCount != null -> stringResource(R.string.sources_files, r.mediaCount)
                        else -> null
                    }
                    val toggleLabel = stringResource(if (r.enabled) R.string.sources_remove else R.string.sources_add, r.title)
                    ListRow(
                        title = r.title, supporting = status,
                        onClick = { onAction(SourcesAction.Toggle(r.chatId, !r.enabled)) },
                        trailing = {
                            Switch(checked = r.enabled, onCheckedChange = { onAction(SourcesAction.Toggle(r.chatId, it)) },
                                modifier = Modifier.semantics { contentDescription = toggleLabel }.testTag("toggle:${r.chatId}"))
                        },
                    )
                }
                if (state.hasMore) item {
                    TextButton(onClick = { onAction(SourcesAction.LoadMore) }, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.sources_more)) }
                }
            }
        }
    }
}
