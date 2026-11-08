package dev.yourstream.core.common

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Exponential backoff with jitter. [maxAttempts] counts the first try.
 * Delay before attempt n (n ≥ 2): min(max, base × factor^(n−2)) ± jitter.
 */
public data class RetryPolicy(
    val maxAttempts: Int = 4,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 60_000,
    val factor: Double = 2.0,
    /** 0..1: each delay is randomised by ± this share, so many clients do not retry in lockstep. */
    val jitter: Double = 0.2,
) {
    public fun delayBefore(attempt: Int, random: Random = Random): Long {
        if (attempt <= 1) return 0
        val raw = (baseDelayMs * Math.pow(factor, (attempt - 2).toDouble())).coerceAtMost(maxDelayMs.toDouble())
        val spread = raw * jitter
        return (raw + if (spread > 0) random.nextDouble(-spread, spread) else 0.0).toLong().coerceIn(0, maxDelayMs)
    }

    public companion object {
        public val NONE: RetryPolicy = RetryPolicy(maxAttempts = 1)
        /** Network calls the user waits for. */
        public val INTERACTIVE: RetryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMs = 500, maxDelayMs = 4_000)
        /** Background work: patient. */
        public val BACKGROUND: RetryPolicy = RetryPolicy(maxAttempts = 6, baseDelayMs = 2_000, maxDelayMs = 5 * 60_000)
    }
}

/** Errors that a later attempt can fix (network, timeouts, rate limits, server errors). */
public fun AppError.isTransient(): Boolean = when (this) {
    is AppError.Network, is AppError.Timeout, is AppError.RateLimited -> true
    is AppError.Remote -> isServerError
    is AppError.Telegram -> code >= 500 || code == 429
    else -> false
}

/**
 * Runs [block] until it succeeds, returns a permanent error, or attempts run out. Rate limits
 * wait at least their Retry-After. Cancellation is never swallowed. [onRetry] sees each retry.
 */
public suspend fun <T> retrying(
    policy: RetryPolicy = RetryPolicy(),
    retryIf: (AppError) -> Boolean = { it.isTransient() },
    onRetry: (attempt: Int, error: AppError, delayMs: Long) -> Unit = { _, _, _ -> },
    random: Random = Random,
    block: suspend (attempt: Int) -> AppResult<T>,
): AppResult<T> {
    var attempt = 1
    while (true) {
        val result = block(attempt)
        if (result is AppResult.Ok) return result
        val error = (result as AppResult.Err).error
        if (attempt >= policy.maxAttempts || !retryIf(error)) return result
        attempt++
        val wait = maxOf(policy.delayBefore(attempt, random), (error as? AppError.RateLimited)?.retryAfterSeconds?.times(1000L) ?: 0)
        onRetry(attempt, error, wait)
        delay(wait)
    }
}
