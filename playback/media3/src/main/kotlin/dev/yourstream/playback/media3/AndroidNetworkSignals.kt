package dev.yourstream.playback.media3

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.yourstream.playback.adaptive.ConnectionType
import dev.yourstream.playback.adaptive.Connectivity
import dev.yourstream.playback.adaptive.DeviceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The OS default network as [Connectivity] (type, metered, bandwidth hint), updated on every change. */
class AndroidConnectivity(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(current())
    val state: StateFlow<Connectivity> = _state.asStateFlow()

    init {
        manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { _state.value = of(capabilities) }
            override fun onLost(network: Network) { _state.value = Connectivity.NONE }
        })
    }

    private fun current(): Connectivity = manager?.let { m -> m.getNetworkCapabilities(m.activeNetwork)?.let(::of) } ?: Connectivity.NONE

    private fun of(c: NetworkCapabilities): Connectivity {
        val type = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            else -> ConnectionType.OTHER
        }
        val metered = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return Connectivity(type, metered, c.linkDownstreamBandwidthKbps.takeIf { it > 0 })
    }
}

fun deviceProfile(context: Context): DeviceProfile {
    val am = context.getSystemService(ActivityManager::class.java)
    return DeviceProfile(
        memoryClassMb = am?.memoryClass ?: 256,
        lowRam = am?.isLowRamDevice ?: false,
        cpuCores = Runtime.getRuntime().availableProcessors(),
    )
}
