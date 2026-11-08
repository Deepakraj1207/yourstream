package dev.yourstream.core.common

/** Expected failures, with a [message] safe to show in a debug screen (never contains secrets). */
public sealed interface AppError {
    public val message: String

    /** No connection, or the request was aborted by the network layer. */
    public data class Network(override val message: String = "Network unavailable") : AppError

    /** The operation did not finish in time (TDLib waits forever while offline, so we bound it). */
    public data class Timeout(val operation: String) : AppError {
        override val message: String get() = "Timed out: $operation"
    }

    /** Telegram FLOOD_WAIT / 429. Retry no sooner than [retryAfterSeconds]. */
    public data class RateLimited(val retryAfterSeconds: Int) : AppError {
        override val message: String get() = "Too many requests, retry in ${retryAfterSeconds}s"
    }

    /** The user gave something Telegram or our validation rejected. [reason] is machine-readable. */
    public data class InvalidInput(val reason: String, override val message: String) : AppError

    /** The session is not (or no longer) authorized. */
    public data class Unauthorized(override val message: String = "Not logged in") : AppError

    /** The operation is not valid in the current state (e.g. submitting a code before a phone). */
    public data class InvalidState(override val message: String) : AppError

    /** Required configuration (e.g. Telegram API credentials) is missing or malformed. */
    public data class NotConfigured(override val message: String) : AppError

    public data class NotFound(override val message: String) : AppError

    /** Any other error reported by Telegram, kept verbatim. */
    public data class Telegram(val code: Int, val rawMessage: String) : AppError {
        override val message: String get() = "Telegram error $code: $rawMessage"
    }

    public data class Unknown(override val message: String, val cause: Throwable? = null) : AppError
}
