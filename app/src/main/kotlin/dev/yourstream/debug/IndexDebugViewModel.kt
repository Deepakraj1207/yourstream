package dev.yourstream.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.media.api.MediaIndexer
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexMode
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.media.api.model.MediaSummary
import dev.yourstream.media.api.model.MediaType
import dev.yourstream.media.indexer.usecase.CancelChatIndexingUseCase
import dev.yourstream.media.indexer.usecase.DisableChatIndexingUseCase
import dev.yourstream.media.indexer.usecase.GetMediaDetailsUseCase
import dev.yourstream.media.indexer.usecase.MediaDetails
import dev.yourstream.media.indexer.usecase.ObserveIndexStatusUseCase
import dev.yourstream.media.indexer.usecase.SearchIndexedMediaUseCase
import dev.yourstream.media.indexer.usecase.StartChatIndexingUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Stage-1 debug state for indexing and search. Talks only to use cases / interfaces. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class IndexDebugViewModel @Inject constructor(
    private val startIndexing: StartChatIndexingUseCase,
    private val cancelIndexing: CancelChatIndexingUseCase,
    private val disableIndexing: DisableChatIndexingUseCase,
    private val observeStatus: ObserveIndexStatusUseCase,
    private val searchMedia: SearchIndexedMediaUseCase,
    private val getDetails: GetMediaDetailsUseCase,
    private val indexer: MediaIndexer,
) : ViewModel() {

    val chats: StateFlow<List<ChatIndexStatus>> =
        observeStatus().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun chatStatus(chatId: Long): Flow<ChatIndexStatus?> = observeStatus(chatId)

    private val _query = MutableStateFlow(MediaQuery())
    val query: StateFlow<MediaQuery> = _query.asStateFlow()

    /** Paged results: only what the list shows is loaded, however big the index is. */
    val results: Flow<PagingData<MediaSummary>> = _query
        .debounce(250)
        .flatMapLatest { searchMedia(it) }
        .cachedIn(viewModelScope)

    val resultCount: StateFlow<Int> = _query.debounce(250).flatMapLatest { searchMedia.count(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setText(text: String) { _query.value = _query.value.copy(text = text) }
    fun setType(type: MediaType?) { _query.value = _query.value.copy(types = setOfNotNull(type)) }
    fun setChatFilter(chatId: Long?) { _query.value = _query.value.copy(chatId = chatId) }
    fun toggleDeleted() { _query.value = _query.value.copy(includeDeleted = !_query.value.includeDeleted) }
    fun toggleDuplicates() { _query.value = _query.value.copy(includeDuplicates = !_query.value.includeDuplicates) }

    fun start(chatId: Long, mode: IndexMode) = viewModelScope.launch {
        val result = startIndexing(chatId, mode)
        _message.value = (result as? AppResult.Err)?.error?.message ?: "Queued $mode for chat $chatId"
    }

    fun cancel(chatId: Long) = viewModelScope.launch { cancelIndexing(chatId); _message.value = "Cancel requested" }
    fun disable(chatId: Long, purge: Boolean) = viewModelScope.launch { disableIndexing(chatId, purge) }
    fun runAll() = viewModelScope.launch { indexer.scheduleAll(); _message.value = "Queued all enabled chats" }

    fun open(mediaId: Long) = viewModelScope.launch { _details.value = getDetails(mediaId) }
    fun closeDetails() { _details.value = null }
    fun dismissMessage() { _message.value = null }
}
