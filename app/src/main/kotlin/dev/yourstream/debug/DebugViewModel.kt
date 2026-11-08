package dev.yourstream.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.log.AppLogger
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatFilter
import dev.yourstream.telegram.api.model.ChatListState
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.TelegramChatDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Stage-1 debug screen state. Deliberately thin: everything it shows comes straight from the
 * Telegram layer's interfaces, which is what this screen exists to prove.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val manager: TelegramManager,
    private val auth: TelegramAuthManager,
    users: TelegramUserRepository,
    private val chats: TelegramChatRepository,
    logger: AppLogger,
) : ViewModel() {

    val status = manager.status
    val connection = manager.connectionState
    val authState = auth.state
    val currentUser = users.currentUser
    val logLines = logger.lines

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _filter = MutableStateFlow(ChatFilter.ALL)
    val filter: StateFlow<ChatFilter> = _filter.asStateFlow()

    private val _listType = MutableStateFlow<ChatListType>(ChatListType.Main)
    val listType: StateFlow<ChatListType> = _listType.asStateFlow()

    val chatList: StateFlow<ChatListState> = combine(_listType, _filter) { list, filter -> list to filter }
        .flatMapLatest { (list, filter) -> chats.chatList(list, filter) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListState.initial(ChatListType.Main))

    private val _selectedChatId = MutableStateFlow<Long?>(null)
    val selectedChatId: StateFlow<Long?> = _selectedChatId.asStateFlow()

    sealed interface Details {
        data object Loading : Details
        data class Loaded(val details: TelegramChatDetails) : Details
        data class Failed(val message: String) : Details
    }

    private val _details = MutableStateFlow<Details?>(null)
    val details: StateFlow<Details?> = _details.asStateFlow()

    private val _showLogs = MutableStateFlow(false)
    val showLogs: StateFlow<Boolean> = _showLogs.asStateFlow()

    init {
        // First page as soon as we are logged in, and whenever the chosen list changes.
        viewModelScope.launch {
            // distinct before filter: a re-login (Ready → … → Ready) must load again.
            combine(auth.state, _listType) { state, list -> state to list }
                .distinctUntilChanged()
                .filter { (state, _) -> state == AuthState.Ready }
                .collect { (_, list) -> chats.loadMore(list) }
        }
    }

    fun submitPhone(phone: String) = run { auth.submitPhoneNumber(phone) }
    fun submitCode(code: String) = run { auth.submitCode(code) }
    fun resendCode() = run { auth.resendCode() }
    fun submitPassword(password: String) = run { auth.submitPassword(password) }
    fun submitEmail(email: String) = run { auth.submitEmailAddress(email) }
    fun submitEmailCode(code: String) = run { auth.submitEmailCode(code) }
    fun requestQrLogin() = run { auth.requestQrCodeLogin() }
    fun logOut() = run {
        _selectedChatId.value = null
        auth.logOut()
    }

    fun reconnect() = run { manager.reconnect() }
    fun restart() = run { manager.restart() }
    fun loadMore() = run { chats.loadMore(_listType.value) }

    fun setFilter(filter: ChatFilter) { _filter.value = filter }
    fun setListType(list: ChatListType) { _listType.value = list }
    fun toggleLogs() { _showLogs.value = !_showLogs.value }
    fun dismissMessage() { _message.value = null }

    fun selectChat(chatId: Long) {
        _selectedChatId.value = chatId
        _details.value = Details.Loading
        viewModelScope.launch {
            _details.value = when (val result = chats.getChatDetails(chatId)) {
                is AppResult.Ok -> Details.Loaded(result.value)
                is AppResult.Err -> Details.Failed(result.error.message)
            }
        }
    }

    fun clearSelection() {
        _selectedChatId.value = null
        _details.value = null
    }

    private fun run(block: suspend () -> AppResult<*>) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            val result = block()
            if (result is AppResult.Err) _message.value = result.error.message
            _busy.value = false
        }
    }
}
