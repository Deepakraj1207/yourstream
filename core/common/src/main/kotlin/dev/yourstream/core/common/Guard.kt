package dev.yourstream.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs code that talks to the outside world (providers, Telegram, files) and turns any
 * unexpected exception into [AppError.Unknown]: a provider changing its response format, a
 * mapping bug or a platform exception must become an error result, never a crash.
 * Cancellation is rethrown.
 */
public suspend inline fun <T> guarded(operation: String, block: () -> AppResult<T>): AppResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Err(AppError.Unknown("$operation failed: ${e::class.simpleName}: ${e.message}", e))
}
