package dev.yourstream.telegram.core.manager

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.core.common.err
import dev.yourstream.core.common.ok
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.api.model.TelegramConfig
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.api.model.TelegramStatus
import dev.yourstream.telegram.core.TelegramScope
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Session lifecycle:
 *
 * ```
 * start() → open TDLib → WaitParameters → (we send parameters) → WaitPhoneNumber | Ready
 * logOut  → LoggingOut → Closed → (we reopen) → WaitParameters → … → WaitPhoneNumber
 * restart → close → Closed → (we reopen) → … → Ready (session kept on disk)
 * stop()  → close → Closed → stays closed
 * ```
 *
 * The session itself lives in TDLib's database directory; giving TDLib the same directory on
 * the next start resumes it without a new login.
 */
@Singleton
class DefaultTelegramManager @Inject constructor(
    private val client: TelegramClient,
    private val config: TelegramConfig,
    @TelegramScope private val scope: CoroutineScope,
    private val logger: Logger,
) : TelegramManager {

    private val _status = MutableStateFlow<TelegramStatus>(TelegramStatus.Stopped)
    override val status: StateFlow<TelegramStatus> = _status.asStateFlow()

    private val _connection = MutableStateFlow(TelegramConnectionState.UNKNOWN)
    override val connectionState: StateFlow<TelegramConnectionState> = _connection.asStateFlow()

    private val lifecycle = Mutex()

    @Volatile private var shouldRun = false
    @Volatile private var lastNetwork: NetworkType? = null
    @Volatile private var reopenAttempts = 0

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                when (event) {
                    is TelegramEvent.ConnectionStateChanged -> {
                        if (_connection.value != event.state) logger.d(TAG, "connection -> ${event.state}")
                        _connection.value = event.state
                    }
                    is TelegramEvent.AuthStateChanged -> onAuthState(event.state)
                    else -> Unit
                }
            }
        }
    }

    private fun onAuthState(state: AuthState) {
        when (state) {
            AuthState.WaitParameters -> scope.launch { sendParameters() }
            AuthState.Closed -> {
                _connection.value = TelegramConnectionState.UNKNOWN
                if (shouldRun) {
                    _status.value = TelegramStatus.Restarting
                    scope.launch { reopen() }
                } else {
                    _status.value = TelegramStatus.Stopped
                }
            }
            AuthState.Unknown, AuthState.LoggingOut, AuthState.Closing -> Unit
            else -> {
                // TDLib reached a stable state: the instance works.
                reopenAttempts = 0
                if (_status.value != TelegramStatus.Running && shouldRun && _status.value !is TelegramStatus.Failed) {
                    _status.value = TelegramStatus.Running
                }
            }
        }
    }

    override suspend fun start(): AppResult<Unit> = lifecycle.withLock {
        config.validationError()?.let { reason ->
            logger.e(TAG, "not starting: $reason")
            _status.value = TelegramStatus.NotConfigured(reason)
            return@withLock AppError.NotConfigured(reason).err()
        }
        if (shouldRun && _status.value !is TelegramStatus.Failed && _status.value != TelegramStatus.Stopped) {
            return@withLock Unit.ok()
        }
        shouldRun = true
        _status.value = TelegramStatus.Starting
        logger.i(TAG, "starting TDLib ($config)")
        openLocked()
    }

    override suspend fun restart(): AppResult<Unit> {
        if (!shouldRun) return start()
        logger.i(TAG, "restart requested")
        _status.value = TelegramStatus.Restarting
        // The Closed event triggers the reopen.
        return client.close()
    }

    override suspend fun stop() {
        lifecycle.withLock {
            shouldRun = false
            logger.i(TAG, "stopping TDLib")
            client.close()
            _status.value = TelegramStatus.Stopped
        }
    }

    override suspend fun reconnect(): AppResult<Unit> {
        if (!shouldRun) return AppError.InvalidState("Telegram is not running.").err()
        logger.i(TAG, "reconnect requested")
        return client.setNetworkType(lastNetwork ?: NetworkType.OTHER)
    }

    override suspend fun onNetworkChanged(type: NetworkType) {
        if (lastNetwork == type) return
        lastNetwork = type
        logger.d(TAG, "network -> $type")
        if (shouldRun) client.setNetworkType(type)
    }

    private suspend fun sendParameters() {
        when (val result = client.setParameters(config)) {
            is AppResult.Ok -> {
                logger.d(TAG, "TDLib parameters accepted")
                lastNetwork?.let { client.setNetworkType(it) }
            }
            is AppResult.Err -> {
                logger.e(TAG, "TDLib rejected parameters: ${result.error.message}")
                _status.value = TelegramStatus.Failed(result.error)
            }
        }
    }

    private suspend fun reopen() {
        val attempt = reopenAttempts++
        val wait = if (attempt == 0) 0L else min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl min(attempt - 1, 5))
        if (wait > 0) {
            logger.w(TAG, "reopening TDLib in ${wait}ms (attempt ${attempt + 1})")
            delay(wait)
        }
        lifecycle.withLock {
            if (!shouldRun) return
            openLocked()
        }
    }

    private suspend fun openLocked(): AppResult<Unit> = when (val result = client.open()) {
        is AppResult.Ok -> result
        is AppResult.Err -> {
            logger.e(TAG, "could not open TDLib: ${result.error.message}")
            shouldRun = false
            _status.value = TelegramStatus.Failed(result.error)
            result
        }
    }

    private companion object {
        const val TAG = "TgManager"
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
