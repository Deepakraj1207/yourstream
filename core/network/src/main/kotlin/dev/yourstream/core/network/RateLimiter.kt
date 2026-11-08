package dev.yourstream.core.network

import dev.yourstream.core.common.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token bucket: at most [permits] requests per [perMillis], waiting (suspending) when empty.
 * Keeps us below a provider's documented limit so 429s are the exception, not the rule.
 */
class RateLimiter(
    private val permits: Int,
    private val perMillis: Long,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private var tokens = permits.toDouble()
    private var refilledAt = clock.nowMillis()

    suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock {
                refill()
                if (tokens >= 1) { tokens -= 1; 0L } else ((1 - tokens) * perMillis / permits).toLong().coerceAtLeast(1)
            }
            if (wait == 0L) return
            delay(wait)
        }
    }

    private fun refill() {
        val now = clock.nowMillis()
        val elapsed = now - refilledAt
        if (elapsed <= 0) return
        tokens = minOf(permits.toDouble(), tokens + elapsed.toDouble() * permits / perMillis)
        refilledAt = now
    }
}
