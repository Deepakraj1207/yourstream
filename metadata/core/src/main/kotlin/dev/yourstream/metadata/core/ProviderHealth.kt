package dev.yourstream.metadata.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.Clock
import dev.yourstream.core.network.ServiceHealth
import dev.yourstream.metadata.api.ProviderId

enum class ProviderState { AVAILABLE, COOLING_DOWN, CIRCUIT_OPEN, DISABLED }

data class ProviderStatus(
    val provider: ProviderId,
    val state: ProviderState,
    val availableAt: Long?,
    val consecutiveFailures: Int,
    val lastError: String?,
)

/** Metadata-provider view of the shared [ServiceHealth] circuit breaker. */
class ProviderHealth(clock: Clock) {
    private val inner = ServiceHealth(clock)

    fun canCall(id: ProviderId): Boolean = inner.canCall(id.value)
    fun onSuccess(id: ProviderId) = inner.onSuccess(id.value)
    fun onError(id: ProviderId, error: AppError) = inner.onError(id.value, error)

    fun status(id: ProviderId): ProviderStatus = inner.status(id.value).let {
        ProviderStatus(id, ProviderState.valueOf(it.state.name), it.availableAt, it.consecutiveFailures, it.lastError)
    }
}
