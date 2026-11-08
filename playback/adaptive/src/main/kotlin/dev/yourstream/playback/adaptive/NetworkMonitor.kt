package dev.yourstream.playback.adaptive

import dev.yourstream.playback.stream.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Network awareness for playback: connection type, metered, measured quality, outages. */
interface NetworkMonitor {
    val state: StateFlow<NetworkState>
    val events: SharedFlow<NetworkEvent>
    /** Re-evaluates time-based conditions (offline grace, stall window). Called by the playback tick. */
    fun tick()
}

data class NetworkThresholds(
    /** Below this the network is POOR (≈ 0.5 Mbit/s). */
    val poorBytesPerSecond: Long = 64 * 1024,
    /** Below this FAIR (≈ 4 Mbit/s); above GOOD. */
    val fairBytesPerSecond: Long = 512 * 1024,
    /** Telegram disconnected this long (with OS connectivity) counts as offline. */
    val offlineGraceMs: Long = 2_000,
    /** Two stalls within this window mark the network POOR whatever the throughput says. */
    val stallWindowMs: Long = 30_000,
)

/**
 * Combines three signals:
 *  - the OS network (type, metered; NONE = offline at once),
 *  - Telegram's connection state (disconnected for longer than a grace period = offline: the OS
 *    may report Wi-Fi while nothing gets through),
 *  - measured throughput and stalls from the streamer ([StreamObserver]).
 */
class DefaultNetworkMonitor(
    connectivity: Flow<Connectivity>,
    telegramConnected: Flow<Boolean>,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val estimator: BandwidthEstimator = BandwidthEstimator(),
    private val thresholds: NetworkThresholds = NetworkThresholds(),
) : NetworkMonitor, StreamObserver {

    private val _state = MutableStateFlow(NetworkState())
    override val state: StateFlow<NetworkState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<NetworkEvent> = _events

    @Volatile private var conn = Connectivity.NONE
    @Volatile private var tgConnected = false
    @Volatile private var tgDisconnectedSince: Long? = clock()
    private val stalls = ArrayDeque<Long>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { connectivity.collect { onConnectivity(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { telegramConnected.collect { onTelegram(it) } }
    }

    @Synchronized fun onConnectivity(c: Connectivity) {
        val old = conn
        conn = c
        if (old.type != c.type && old.type != ConnectionType.NONE && c.type != ConnectionType.NONE) {
            estimator.onNetworkChanged()
            _events.tryEmit(NetworkEvent.Switched(old.type, c.type))
        }
        recompute()
    }

    @Synchronized fun onTelegram(connected: Boolean) {
        tgConnected = connected
        tgDisconnectedSince = if (connected) null else (tgDisconnectedSince ?: clock())
        recompute()
    }

    override fun onTransfer(fileId: Int, bytes: Long, nanos: Long) {
        estimator.addSample(bytes, nanos)
        synchronized(this) { recompute() }
    }

    override fun onDataArrived(fileId: Int, nanos: Long) = estimator.addLatency(nanos / 1_000_000)

    override fun onStall(fileId: Int) = synchronized(this) {
        stalls.addLast(clock())
        recompute()
    }

    @Synchronized override fun tick() = recompute()

    private fun recompute() {
        val now = clock()
        while (stalls.isNotEmpty() && now - stalls.first() > thresholds.stallWindowMs) stalls.removeFirst()
        val old = _state.value
        val offline = conn.type == ConnectionType.NONE ||
            (!tgConnected && (tgDisconnectedSince?.let { now - it >= thresholds.offlineGraceMs } ?: false))
        val bps = estimator.estimate()
        val quality = when {
            offline -> NetworkQuality.OFFLINE
            stalls.size >= 2 -> NetworkQuality.POOR
            bps == null -> conn.downlinkKbps?.let { classify(it * 1000L / 8 / 2) } ?: NetworkQuality.UNKNOWN // OS hint, halved: it is optimistic
            else -> classify(bps)
        }
        val offlineSince = if (quality == NetworkQuality.OFFLINE) (old.offlineSinceMs ?: now) else null
        val next = NetworkState(conn, tgConnected, quality, bps, estimator.latencyMs(), offlineSince, stalls.size)
        if (next == old) return
        _state.value = next
        if (old.quality != quality) {
            if (quality == NetworkQuality.OFFLINE) _events.tryEmit(NetworkEvent.Lost)
            else if (old.quality == NetworkQuality.OFFLINE) _events.tryEmit(NetworkEvent.Restored(now - (old.offlineSinceMs ?: now)))
            _events.tryEmit(NetworkEvent.QualityChanged(old.quality, quality))
        }
    }

    private fun classify(bps: Long) = when {
        bps < thresholds.poorBytesPerSecond -> NetworkQuality.POOR
        bps < thresholds.fairBytesPerSecond -> NetworkQuality.FAIR
        else -> NetworkQuality.GOOD
    }
}
