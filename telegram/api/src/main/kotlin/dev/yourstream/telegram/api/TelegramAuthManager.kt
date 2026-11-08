package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * The login flow. Observe [state] and call the method matching it. Each call returns once
 * Telegram accepted (or rejected) the input; the next step arrives through [state].
 */
public interface TelegramAuthManager {
    public val state: StateFlow<AuthState>

    /** Accepts "+49 170 1234567", "0049...", "(49) 170-..."; normalises to "+digits". */
    public suspend fun submitPhoneNumber(phoneNumber: String): AppResult<Unit>

    public suspend fun submitCode(code: String): AppResult<Unit>

    /** Requests the code again via [dev.yourstream.telegram.api.model.CodeInfo.nextType]. */
    public suspend fun resendCode(): AppResult<Unit>

    /** Two-step verification password. */
    public suspend fun submitPassword(password: String): AppResult<Unit>

    public suspend fun submitEmailAddress(email: String): AppResult<Unit>

    public suspend fun submitEmailCode(code: String): AppResult<Unit>

    /** Switches to QR login; [state] becomes WaitOtherDeviceConfirmation with a link to show. */
    public suspend fun requestQrCodeLogin(): AppResult<Unit>

    /** Ends the session on Telegram's side and wipes local session data. */
    public suspend fun logOut(): AppResult<Unit>
}
