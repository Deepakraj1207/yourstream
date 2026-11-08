package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.api.model.TelegramStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the Telegram session lifecycle: starts TDLib, supplies its parameters, recreates the
 * instance after logout, and forwards network changes so TDLib reconnects quickly.
 *
 * Login itself is [TelegramAuthManager]; data access is the repositories.
 */
public interface TelegramManager {
    public val status: StateFlow<TelegramStatus>
    public val connectionState: StateFlow<TelegramConnectionState>

    /** Starts TDLib if needed. Safe to call repeatedly. A saved session resumes without login. */
    public suspend fun start(): AppResult<Unit>

    /** Closes and recreates the TDLib instance, keeping the saved session. For stuck states. */
    public suspend fun restart(): AppResult<Unit>

    /** Closes TDLib; it stays closed until [start]. */
    public suspend fun stop()

    /** Asks TDLib to re-check connectivity now (it would also retry on its own). */
    public suspend fun reconnect(): AppResult<Unit>

    /** Call when the device's network changes. */
    public suspend fun onNetworkChanged(type: NetworkType)
}
