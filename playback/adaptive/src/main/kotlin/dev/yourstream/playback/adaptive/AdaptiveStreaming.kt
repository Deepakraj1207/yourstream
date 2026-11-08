package dev.yourstream.playback.adaptive

import dev.yourstream.playback.stream.TelegramFileStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Ties the parts together for one player:
 *
 *   player sample ─▶ NetworkMonitor.tick ─▶ BufferManager.update ─▶ policy
 *                                                  │                  ├─▶ load control (RAM buffer thresholds)
 *                                                  │                  ├─▶ streamer.setReadAhead (disk window)
 *                                                  │                  ├─▶ risk HIGH → pause prefetch, refresh downloads
 *                                                  │                  └─▶ PrefetchManager.onSample
 *   streamer transfers ─▶ NetworkMonitor (throughput, latency, stalls)
 *   network events: switch / reconnect ─▶ streamer.refreshDownloads; offline ─▶ streamer waits instead of failing
 */
class AdaptiveStreaming(
    private val streamer: TelegramFileStreamer,
    val governor: DownloadGovernor,
    val network: NetworkMonitor,
    val buffer: BufferManager,
    private val scope: CoroutineScope,
    val prefetch: PrefetchManager? = null,
) {
    @Volatile var policy: BufferPolicy = buffer.policy; private set
    private var riskPaused = false

    init {
        // (The monitor is registered as the streamer's observer once, where both are created:
        // see connect(); one AdaptiveStreaming exists per player.)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            network.events.collect { e ->
                when (e) {
                    is NetworkEvent.Switched, is NetworkEvent.Restored -> streamer.refreshDownloads()
                    NetworkEvent.Lost -> governor.pausePrefetch(OFFLINE)
                    is NetworkEvent.QualityChanged -> if (e.from == NetworkQuality.OFFLINE) governor.resumePrefetch(OFFLINE)
                }
            }
        }
    }

    /** One playback sample for the file being played. Returns the policy now in force. */
    fun onSample(sample: PlaybackSample, fileId: Int?): BufferPolicy {
        network.tick()
        val p = buffer.update(sample, network.state.value)
        policy = p
        fileId?.let { streamer.setReadAhead(it, p.readAheadBytes) }
        when {
            p.risk == RebufferRisk.HIGH && !riskPaused -> {
                riskPaused = true
                // Everything for the playing file: stop prefetches and make sure its download is live.
                scope.launch { governor.pausePrefetch(RISK); streamer.refreshDownloads() }
            }
            p.risk == RebufferRisk.NONE && riskPaused -> { riskPaused = false; governor.resumePrefetch(RISK) }
        }
        prefetch?.onSample(sample, p)
        return p
    }

    companion object {
        private const val RISK = "rebuffer risk"
        private const val OFFLINE = "offline"

        /** Wires a (singleton) monitor to a (singleton) streamer: throughput samples in, offline signal out. */
        fun connect(streamer: TelegramFileStreamer, monitor: DefaultNetworkMonitor) {
            streamer.addObserver(monitor)
            streamer.offlineSignal = { monitor.state.value.isOffline }
        }
    }
}
