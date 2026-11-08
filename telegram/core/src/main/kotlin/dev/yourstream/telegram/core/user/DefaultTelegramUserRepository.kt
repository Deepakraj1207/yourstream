package dev.yourstream.telegram.core.user

import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.core.common.getOrNull
import dev.yourstream.core.common.onErr
import dev.yourstream.core.common.onOk
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.TelegramUser
import dev.yourstream.telegram.core.TelegramScope
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTelegramUserRepository @Inject constructor(
    private val client: TelegramClient,
    @TelegramScope private val scope: CoroutineScope,
    private val logger: Logger,
) : TelegramUserRepository {

    private val _currentUser = MutableStateFlow<TelegramUser?>(null)
    override val currentUser: StateFlow<TelegramUser?> = _currentUser.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                when (event) {
                    is TelegramEvent.AuthStateChanged -> when (event.state) {
                        AuthState.Ready -> scope.launch { refreshCurrentUser() }
                        AuthState.LoggingOut, AuthState.Closed, AuthState.WaitPhoneNumber -> _currentUser.value = null
                        else -> Unit
                    }
                    is TelegramEvent.UserChanged -> {
                        if (event.user.id == _currentUser.value?.id) _currentUser.value = event.user
                    }
                    else -> Unit
                }
            }
        }
        // If created after login, load the user now.
        scope.launch {
            if (_currentUser.value == null && client.getAuthState().getOrNull() == AuthState.Ready) refreshCurrentUser()
        }
    }

    override suspend fun refreshCurrentUser(): AppResult<TelegramUser> =
        client.getMe()
            .onOk {
                logger.i(TAG, "logged in as user ${it.id}")
                _currentUser.value = it
            }
            .onErr { logger.w(TAG, "getMe failed: ${it.message}") }

    override suspend fun getUser(userId: Long): AppResult<TelegramUser> = client.getUser(userId)

    private companion object {
        const val TAG = "TgUser"
    }
}
