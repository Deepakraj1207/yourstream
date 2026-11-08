package dev.yourstream.metadata.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.Clock
import dev.yourstream.metadata.api.ProviderId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class ProviderState { AVAILABLE, COOLING_DOWN, CIRCUIT_OPEN, DISABLED }

data class ProviderStatus(
    val provider: ProviderId,
    val state: ProviderState,
    val availableAt: Long?,
    val consecutiveFailures: Int,
    val lastError: String?,
)

/**
 * Per-provider circuit breaker:
 *  - 429 → cool down for Retry-After (the manager uses another provider meanwhile);
 *  - 3+ consecutive transient failures → open for 30 s, doubling up to 15 min;
 *  - rejected key (401/403) → disabled for an hour;
 *  - any success closes the circuit.
 */
class ProviderHealth(private val clock: Clock) {

    private class Entry {
        @Volatile var failures = 0
        @Volatile var unavailableUntil = 0L
        @Volatile var state = ProviderState.AVAILABLE
        @Volatile var lastError: String? = null
    }

    private val entries = ConcurrentHashMap<ProviderId, Entry>()
    private fun entry(id: ProviderId) = entries.getOrPut(id) { Entry() }

    fun canCall(id: ProviderId): Boolean {
        val e = entry(id)
        if (clock.nowMillis() >= e.unavailableUntil) {
            if (e.state != ProviderState.AVAILABLE) e.state = ProviderState.AVAILABLE
            return true
        }
        return false
    }

    fun onSuccess(id: ProviderId) {
        val e = entry(id)
        e.failures = 0; e.unavailableUntil = 0; e.state = ProviderState.AVAILABLE
    }

    fun onError(id: ProviderId, error: AppError) {
        val e = entry(id)
        e.lastError = error.message
        val now = clock.nowMillis()
        when (error) {
            is AppError.RateLimited -> {
                e.state = ProviderState.COOLING_DOWN
                e.unavailableUntil = now + error.retryAfterSeconds.coerceAtLeast(1) * 1000L
            }
            is AppError.NotConfigured -> {
                e.state = ProviderState.DISABLED
                e.unavailableUntil = now + DISABLE_MILLIS
            }
            is AppError.Network, is AppError.Timeout, is AppError.Unknown -> trip(e, now)
            is AppError.Remote -> if (error.isServerError) trip(e, now)
            else -> Unit // NotFound, InvalidInput: the provider works fine
        }
    }

    private fun trip(e: Entry, now: Long) {
        e.failures++
        if (e.failures >= FAILURES_TO_OPEN) {
            val backoff = min(MAX_OPEN_MILLIS, BASE_OPEN_MILLIS shl min(e.failures - FAILURES_TO_OPEN, 10))
            e.state = ProviderState.CIRCUIT_OPEN
            e.unavailableUntil = now + backoff
        }
    }

    fun status(id: ProviderId): ProviderStatus {
        val e = entry(id)
        canCall(id)
        return ProviderStatus(id, e.state, e.unavailableUntil.takeIf { it > clock.nowMillis() }, e.failures, e.lastError)
    }

    private companion object {
        const val FAILURES_TO_OPEN = 3
        const val BASE_OPEN_MILLIS = 30_000L
        const val MAX_OPEN_MILLIS = 15 * 60_000L
        const val DISABLE_MILLIS = 60 * 60_000L
    }
}
