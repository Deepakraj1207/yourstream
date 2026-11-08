package dev.yourstream.playback.stream

import dev.yourstream.core.common.AppError
import java.io.IOException

/**
 * Failures of a Telegram stream. They are [IOException]s so players treat them as load errors;
 * [retryable] tells whether trying again later can help.
 */
sealed class TelegramStreamException(message: String, val retryable: Boolean, cause: Throwable? = null) : IOException(message, cause) {

    /** The file cannot be obtained: deleted message, not downloadable, gone from the server. */
    class Unavailable(val fileId: Int, reason: String) : TelegramStreamException("File $fileId unavailable: $reason", retryable = false)

    /** No data arrived at the read position in time (network down or very slow). */
    class Stalled(val fileId: Int, val position: Long, val waitedMs: Long) :
        TelegramStreamException("No data for file $fileId at byte $position for ${waitedMs}ms", retryable = true)

    /** Telegram answered with an error. */
    class Telegram(val fileId: Int, val error: AppError) :
        TelegramStreamException("Telegram error for file $fileId: ${error.message}", retryable = error.isRetryable())

    /** The requested position is past the end of the file. */
    class OutOfRange(val fileId: Int, val position: Long, val size: Long) :
        TelegramStreamException("Position $position is beyond the end ($size) of file $fileId", retryable = false)
}

internal fun AppError.isRetryable(): Boolean = when (this) {
    is AppError.Network, is AppError.Timeout, is AppError.RateLimited, is AppError.Unknown -> true
    is AppError.Remote -> isServerError
    is AppError.Telegram -> code >= 500 || code == 429
    else -> false
}
