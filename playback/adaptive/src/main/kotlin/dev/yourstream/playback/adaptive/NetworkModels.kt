package dev.yourstream.playback.adaptive

enum class ConnectionType { WIFI, ETHERNET, CELLULAR, OTHER, NONE }

/** What the OS says about the active network. */
data class Connectivity(
    val type: ConnectionType,
    /** Metered (mobile data, hotspot): bandwidth costs the user money. */
    val metered: Boolean,
    /** OS bandwidth hint (NetworkCapabilities.linkDownstreamBandwidthKbps), if any. Optimistic. */
    val downlinkKbps: Int? = null,
) {
    companion object {
        val NONE = Connectivity(ConnectionType.NONE, metered = false)
        val WIFI = Connectivity(ConnectionType.WIFI, metered = false)
        val CELLULAR = Connectivity(ConnectionType.CELLULAR, metered = true)
    }
}

enum class NetworkQuality { OFFLINE, POOR, FAIR, GOOD, UNKNOWN }

data class NetworkState(
    val connectivity: Connectivity = Connectivity.NONE,
    val telegramConnected: Boolean = false,
    val quality: NetworkQuality = NetworkQuality.UNKNOWN,
    /** Measured download throughput (conservative estimate), bytes/s; null until enough samples. */
    val bytesPerSecond: Long? = null,
    /** Typical wait from request to first data (ms). */
    val latencyMs: Long? = null,
    /** Since when the device is offline (monitor clock), else null. */
    val offlineSinceMs: Long? = null,
    val recentStalls: Int = 0,
) {
    val isOffline: Boolean get() = quality == NetworkQuality.OFFLINE
    val isMetered: Boolean get() = connectivity.metered
}

sealed interface NetworkEvent {
    /** Active network type changed (Wi-Fi ↔ mobile …). Old throughput samples no longer apply. */
    data class Switched(val from: ConnectionType, val to: ConnectionType) : NetworkEvent
    data object Lost : NetworkEvent
    data class Restored(val offlineMs: Long) : NetworkEvent
    data class QualityChanged(val from: NetworkQuality, val to: NetworkQuality) : NetworkEvent
}
