package dev.yourstream.core.network

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class ServiceState { AVAILABLE, COOLING_DOWN, CIRCUIT_OPEN, DISABLED }

data class ServiceStatus(
    val id: String,
    val state: ServiceState,
    val availableAt: Long?,
    val consecutiveFailures: Int,
    val lastError: String?,
)

/**
 * Per-service circuit breaker shared by metadata and catalog providers:
 *  - 429 → cool down for Retry-After;
 *  - 3+ consecutive transient failures → open for 30 s, doubling up to 15 min;
 *  - rejected key (401/403) → disabled for an hour;
 *  - any success closes the circuit.
 */
class ServiceHealth(private val clock: Clock) {

    private class Entry {
        @Volatile var failures = 0
        @Volatile var unavailableUntil = 0L
        @Volatile var state = ServiceState.AVAILABLE
        @Volatile var lastError: String? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private fun entry(id: String) = entries.getOrPut(id) { Entry() }

    fun canCall(id: String): Boolean {
        val e = entry(id)
        if (clock.nowMillis() >= e.unavailableUntil) {
            if (e.state != ServiceState.AVAILABLE) e.state = ServiceState.AVAILABLE
            return true
        }
        return false
    }

    fun onSuccess(id: String) {
        val e = entry(id)
        e.failures = 0; e.unavailableUntil = 0; e.state = ServiceState.AVAILABLE
    }

    fun onError(id: String, error: AppError) {
        val e = entry(id)
        e.lastError = error.message
        val now = clock.nowMillis()
        when (error) {
            is AppError.RateLimited -> {
                e.state = ServiceState.COOLING_DOWN
                e.unavailableUntil = now + error.retryAfterSeconds.coerceAtLeast(1) * 1000L
            }
            is AppError.NotConfigured -> {
                e.state = ServiceState.DISABLED
                e.unavailableUntil = now + DISABLE_MILLIS
            }
            is AppError.Network, is AppError.Timeout, is AppError.Unknown -> trip(e, now)
            is AppError.Remote -> if (error.isServerError) trip(e, now)
            else -> Unit
        }
    }

    private fun trip(e: Entry, now: Long) {
        e.failures++
        if (e.failures >= FAILURES_TO_OPEN) {
            val backoff = min(MAX_OPEN_MILLIS, BASE_OPEN_MILLIS shl min(e.failures - FAILURES_TO_OPEN, 10))
            e.state = ServiceState.CIRCUIT_OPEN
            e.unavailableUntil = now + backoff
        }
    }

    /** Seconds until [id] may be called again (≥ 1), for RateLimited answers while it is blocked. */
    fun waitSeconds(id: String): Int = ((entry(id).unavailableUntil - clock.nowMillis()) / 1000).toInt().coerceAtLeast(1)

    fun status(id: String): ServiceStatus {
        val e = entry(id)
        canCall(id)
        return ServiceStatus(id, e.state, e.unavailableUntil.takeIf { it > clock.nowMillis() }, e.failures, e.lastError)
    }

    private companion object {
        const val FAILURES_TO_OPEN = 3
        const val BASE_OPEN_MILLIS = 30_000L
        const val MAX_OPEN_MILLIS = 15 * 60_000L
        const val DISABLE_MILLIS = 60 * 60_000L
    }
}
