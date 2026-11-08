package dev.yourstream.telegram.core.error

import dev.yourstream.core.common.AppError

/**
 * Turns a TDLib error (code + message) into an [AppError].
 *
 * TDLib reports Telegram RPC errors with their HTTP-like code and the server's constant as
 * message (e.g. 400 "PHONE_CODE_INVALID"), FLOOD_WAIT as 429 "Too Many Requests: retry after N",
 * and its own failures as 400/406/500 with free text.
 */
object TelegramErrorMapper {

    private val RETRY_AFTER = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)
    private val FLOOD_WAIT = Regex("FLOOD_WAIT_(\\d+)")

    private val FRIENDLY = mapOf(
        "PHONE_NUMBER_INVALID" to "The phone number is invalid.",
        "PHONE_NUMBER_BANNED" to "This phone number is banned from Telegram.",
        "PHONE_NUMBER_FLOOD" to "Too many login attempts for this number. Try later.",
        "PHONE_NUMBER_UNOCCUPIED" to "This number has no Telegram account.",
        "PHONE_CODE_INVALID" to "The code is wrong.",
        "PHONE_CODE_EMPTY" to "Enter the code.",
        "PHONE_CODE_EXPIRED" to "The code expired. Request a new one.",
        "PASSWORD_HASH_INVALID" to "Wrong password.",
        "EMAIL_INVALID" to "The email address is invalid.",
        "EMAIL_CODE_INVALID" to "The email code is wrong.",
        "CODE_INVALID" to "The code is wrong.",
        "SEND_CODE_UNAVAILABLE" to "No other way to send the code is available.",
        "AUTH_KEY_UNREGISTERED" to "The session has ended. Log in again.",
        "SESSION_REVOKED" to "The session was terminated from another device.",
        "USER_DEACTIVATED" to "This account was deleted or deactivated.",
        "USER_DEACTIVATED_BAN" to "This account is banned.",
        "API_ID_INVALID" to "The Telegram API id/hash pair is not valid.",
        "API_ID_PUBLISHED_FLOOD" to "This API id was published and is blocked. Use your own.",
    )

    fun map(code: Int, message: String): AppError {
        val text = message.trim()
        RETRY_AFTER.find(text)?.let { return AppError.RateLimited(it.groupValues[1].toInt()) }
        FLOOD_WAIT.find(text)?.let { return AppError.RateLimited(it.groupValues[1].toInt()) }
        if (code == 429) return AppError.RateLimited(retryAfterSeconds = 0)

        FRIENDLY[text]?.let { friendly ->
            return when (text) {
                "AUTH_KEY_UNREGISTERED", "SESSION_REVOKED", "USER_DEACTIVATED", "USER_DEACTIVATED_BAN" ->
                    AppError.Unauthorized(friendly)
                "API_ID_INVALID", "API_ID_PUBLISHED_FLOOD" -> AppError.NotConfigured(friendly)
                else -> AppError.InvalidInput(reason = text, message = friendly)
            }
        }

        return when {
            code == 401 -> AppError.Unauthorized(text.ifEmpty { "Unauthorized" })
            code == 404 -> AppError.NotFound(text.ifEmpty { "Not found" })
            // TDLib uses 500 "Request aborted" / connection failures for network-level problems.
            code == 500 && text.contains("abort", ignoreCase = true) -> AppError.Network(text)
            text.contains("network", ignoreCase = true) || text.contains("connection", ignoreCase = true) ->
                AppError.Network(text)
            // Calling a method in the wrong authorization state.
            code == 400 && text.contains("unexpected", ignoreCase = true) -> AppError.InvalidState(text)
            else -> AppError.Telegram(code, text)
        }
    }
}
