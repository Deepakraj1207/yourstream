package dev.yourstream.telegram.core.auth

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.core.common.err
import dev.yourstream.core.common.onErr
import dev.yourstream.core.common.onOk
import dev.yourstream.core.common.redactPhone
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.TelegramConfig
import dev.yourstream.telegram.core.TelegramScope
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class DefaultTelegramAuthManager @Inject constructor(
    private val client: TelegramClient,
    config: TelegramConfig,
    @TelegramScope scope: CoroutineScope,
    private val logger: Logger,
) : TelegramAuthManager {

    private val _state = MutableStateFlow<AuthState>(
        config.validationError()?.let { AuthState.NotConfigured(it) } ?: AuthState.Unknown,
    )
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** One login request at a time; a double tap must not send two codes. */
    private val inFlight = Mutex()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                if (event is TelegramEvent.AuthStateChanged && _state.value !is AuthState.NotConfigured) {
                    if (_state.value != event.state) logger.i(TAG, "auth state -> ${event.state.logName()}")
                    _state.value = event.state
                }
            }
        }
        // If created after TDLib started, catch up with the current state.
        if (_state.value == AuthState.Unknown) {
            scope.launch {
                client.getAuthState().onOk { current -> _state.compareAndSet(AuthState.Unknown, current) }
            }
        }
    }

    override suspend fun submitPhoneNumber(phoneNumber: String): AppResult<Unit> {
        val phone = LoginInput.normalizePhone(phoneNumber)
            ?: return AppError.InvalidInput(
                reason = "PHONE_FORMAT",
                message = "Enter the number in international format, e.g. +91 98765 43210.",
            ).err()
        return request("submitPhoneNumber", PHONE_ALLOWED) {
            logger.i(TAG, "sending phone number ${redactPhone(phone)}")
            client.setPhoneNumber(phone)
        }
    }

    override suspend fun submitCode(code: String): AppResult<Unit> {
        val clean = LoginInput.normalizeCode(code)
            ?: return AppError.InvalidInput("CODE_EMPTY", "Enter the code.").err()
        return request("submitCode", setOf(AuthState.WaitCode::class)) { client.checkCode(clean) }
    }

    override suspend fun resendCode(): AppResult<Unit> {
        val current = _state.value
        if (current is AuthState.WaitCode && current.info.nextType == null) {
            return AppError.InvalidState("Telegram offers no other way to send the code.").err()
        }
        return request("resendCode", setOf(AuthState.WaitCode::class)) { client.resendCode() }
    }

    override suspend fun submitPassword(password: String): AppResult<Unit> {
        // Passwords are used exactly as typed: no trimming.
        if (password.isEmpty()) return AppError.InvalidInput("PASSWORD_EMPTY", "Enter your password.").err()
        return request("submitPassword", setOf(AuthState.WaitPassword::class)) { client.checkPassword(password) }
    }

    override suspend fun submitEmailAddress(email: String): AppResult<Unit> {
        if (!LoginInput.isPlausibleEmail(email)) {
            return AppError.InvalidInput("EMAIL_FORMAT", "Enter a valid email address.").err()
        }
        return request("submitEmailAddress", setOf(AuthState.WaitEmailAddress::class)) {
            client.setEmailAddress(email.trim())
        }
    }

    override suspend fun submitEmailCode(code: String): AppResult<Unit> {
        val clean = LoginInput.normalizeCode(code)
            ?: return AppError.InvalidInput("CODE_EMPTY", "Enter the code.").err()
        return request("submitEmailCode", setOf(AuthState.WaitEmailCode::class)) { client.checkEmailCode(clean) }
    }

    override suspend fun requestQrCodeLogin(): AppResult<Unit> =
        request("requestQrCodeLogin", PHONE_ALLOWED) { client.requestQrCode() }

    override suspend fun logOut(): AppResult<Unit> {
        val current = _state.value
        if (current in NOT_STARTED || current is AuthState.NotConfigured) {
            return AppError.InvalidState("Telegram is not running.").err()
        }
        if (current == AuthState.LoggingOut || current == AuthState.Closing || current == AuthState.Closed) {
            return AppResult.Ok(Unit)
        }
        logger.i(TAG, "logging out")
        return client.logOut()
    }

    private suspend fun request(
        name: String,
        allowed: Set<KClass<out AuthState>>,
        block: suspend () -> AppResult<Unit>,
    ): AppResult<Unit> {
        val current = _state.value
        if (current is AuthState.NotConfigured) return AppError.NotConfigured(current.reason).err()
        if (allowed.none { it.isInstance(current) }) {
            return AppError.InvalidState("$name is not possible while ${current.logName()}.").err()
        }
        if (!inFlight.tryLock()) return AppError.InvalidState("Another login request is still running.").err()
        return try {
            block()
                .onOk { logger.d(TAG, "$name accepted") }
                .onErr { logger.w(TAG, "$name rejected: ${it.message}") }
        } finally {
            inFlight.unlock()
        }
    }

    private companion object {
        const val TAG = "TgAuth"

        /** States in which TDLib accepts a (new) phone number or a QR login request. */
        val PHONE_ALLOWED: Set<KClass<out AuthState>> = setOf(
            AuthState.WaitPhoneNumber::class,
            AuthState.WaitCode::class,
            AuthState.WaitPassword::class,
            AuthState.WaitEmailAddress::class,
            AuthState.WaitEmailCode::class,
            AuthState.WaitRegistration::class,
            AuthState.WaitOtherDeviceConfirmation::class,
        )

        val NOT_STARTED: Set<AuthState> = setOf(AuthState.Unknown, AuthState.WaitParameters)
    }
}

/** State name without personal data (WaitCode carries the phone number). */
internal fun AuthState.logName(): String = when (this) {
    is AuthState.WaitCode -> "WaitCode(${info.type})"
    is AuthState.WaitPassword -> "WaitPassword"
    is AuthState.WaitEmailCode -> "WaitEmailCode"
    is AuthState.WaitOtherDeviceConfirmation -> "WaitOtherDeviceConfirmation"
    is AuthState.NotConfigured -> "NotConfigured"
    is AuthState.Unsupported -> "Unsupported($name)"
    else -> toString()
}
