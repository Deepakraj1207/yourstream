package dev.yourstream.telegram.tdlib

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.core.common.Logger
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.core.TelegramScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells TDLib about network changes. TDLib would notice by itself eventually; telling it makes
 * reconnection after Wi-Fi/mobile switches or airplane mode near-instant.
 */
@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: TelegramManager,
    @TelegramScope private val scope: CoroutineScope,
    private val logger: Logger,
) {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        report(typeOf(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    report(typeOf(capabilities))
                }

                override fun onLost(network: Network) {
                    report(NetworkType.NONE)
                }
            })
        } catch (e: SecurityException) {
            logger.w(TAG, "cannot watch the network (missing ACCESS_NETWORK_STATE?)", e)
        }
    }

    private fun report(type: NetworkType) {
        scope.launch { manager.onNetworkChanged(type) }
    }

    private fun typeOf(capabilities: NetworkCapabilities?): NetworkType = when {
        capabilities == null -> NetworkType.NONE
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) NetworkType.MOBILE
            else NetworkType.MOBILE_ROAMING
        else -> NetworkType.OTHER
    }

    private companion object {
        const val TAG = "TgNetwork"
    }
}
