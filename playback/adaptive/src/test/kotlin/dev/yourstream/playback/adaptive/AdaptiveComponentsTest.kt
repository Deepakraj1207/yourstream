package dev.yourstream.playback.adaptive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KB = 1024L
private const val MB = 1024L * 1024L

class BandwidthEstimatorTest {
    @Test
    fun convergesAndIsQuickToBelieveBadNewsSlowForGood() {
        val e = BandwidthEstimator()
        assertNull(e.estimate(), "no estimate before enough bytes")
        repeat(20) { e.addSample(512 * KB, 500_000_000) } // 1 MB/s
        assertTrue(e.estimate()!! in (950 * KB)..(1050 * KB), "estimate ${e.estimate()}")
        repeat(4) { e.addSample(64 * KB, 500_000_000) } // drops to 128 KB/s for 2 s
        val afterDrop = e.estimate()!!
        assertTrue(afterDrop < 600 * KB, "fast average pulls the estimate down within 2 s: $afterDrop")
        repeat(40) { e.addSample(512 * KB, 500_000_000) }
        val recovered = e.estimate()!!
        repeat(4) { e.addSample(4 * MB, 500_000_000) } // brief 8 MB/s spike
        assertTrue(e.estimate()!! < recovered * 3, "a 2 s spike does not triple the estimate")
    }

    @Test
    fun networkChangeForgetsTheOldNetwork() {
        val e = BandwidthEstimator()
        repeat(20) { e.addSample(2 * MB, 500_000_000) }
        e.onNetworkChanged()
        repeat(6) { e.addSample(100 * KB, 500_000_000) }
        assertTrue(e.estimate()!! < 1 * MB, "mobile estimate not dominated by Wi-Fi history: ${e.estimate()}")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {
    private var now = 0L
    private val conn = MutableStateFlow(Connectivity.WIFI)
    private val tg = MutableStateFlow(true)

    private fun TestScope.monitor() = DefaultNetworkMonitor(conn, tg, backgroundScope, { now })

    @Test
    fun classifiesQualityAndReportsSwitchesOutagesAndRecovery() = runTest {
        val m = monitor()
        val events = mutableListOf<NetworkEvent>()
        backgroundScope.launchCollect(m, events)
        runCurrent()
        assertEquals(NetworkQuality.UNKNOWN, m.state.value.quality)
        repeat(10) { m.onTransfer(1, 400 * KB, 500_000_000) } // 800 KB/s
        assertEquals(NetworkQuality.GOOD, m.state.value.quality)

        conn.value = Connectivity.CELLULAR; runCurrent()
        assertTrue(events.any { it == NetworkEvent.Switched(ConnectionType.WIFI, ConnectionType.CELLULAR) }, "events $events")
        assertTrue(m.state.value.isMetered, "state ${m.state.value}")
        repeat(10) { m.onTransfer(1, 20 * KB, 500_000_000) } // 40 KB/s
        assertEquals(NetworkQuality.POOR, m.state.value.quality)

        // Telegram disconnects: offline only after the grace period.
        tg.value = false; runCurrent()
        now += 1_000; m.tick()
        assertFalse(m.state.value.isOffline, "brief reconnects are not outages")
        now += 1_500; m.tick(); runCurrent()
        assertTrue(m.state.value.isOffline, "state ${m.state.value}")
        assertTrue(NetworkEvent.Lost in events, "events $events")
        now += 5_000
        tg.value = true; runCurrent()
        assertFalse(m.state.value.isOffline)
        assertTrue(events.any { it is NetworkEvent.Restored && it.offlineMs >= 5_000 }, "events $events")

        conn.value = Connectivity.NONE; runCurrent()
        assertTrue(m.state.value.isOffline, "no OS network = offline at once")
    }

    @Test
    fun repeatedStallsMarkTheNetworkPoor() = runTest {
        val m = monitor()
        repeat(10) { m.onTransfer(1, 2 * MB, 500_000_000) }
        assertEquals(NetworkQuality.GOOD, m.state.value.quality)
        m.onStall(1); m.onStall(1)
        assertEquals(NetworkQuality.POOR, m.state.value.quality)
        now += 31_000; m.tick()
        assertEquals(NetworkQuality.GOOD, m.state.value.quality, "stalls age out")
    }

    private fun CoroutineScope.launchCollect(m: NetworkMonitor, into: MutableList<NetworkEvent>) =
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { m.events.collect { into += it } }
}

class BufferManagerTest {
    private val bitrate = 312_500L // 2.5 Mbit/s
    private fun sample(t: Long, ahead: Long, phase: PlaybackPhase = PlaybackPhase.PLAYING, pos: Long = 60_000, duration: Long = 1_800_000) =
        PlaybackSample(t, phase, pos, pos + ahead, duration, bitrate)
    private fun net(bps: Long?, quality: NetworkQuality, metered: Boolean = false) =
        NetworkState(Connectivity(if (metered) ConnectionType.CELLULAR else ConnectionType.WIFI, metered), true, quality, bps)

    @Test
    fun goodNetworkStartsFastSlowNetworkBuffersMore() {
        val good = BufferManager().update(sample(0, 20_000), net(4 * MB, NetworkQuality.GOOD))
        assertEquals(1_000, good.startPlaybackMs)
        assertEquals(50_000, good.maxBufferMs)
        assertTrue(good.prefetchAllowed)

        val tight = BufferManager().update(sample(0, 20_000), net(330 * KB, NetworkQuality.FAIR)) // headroom 1.08
        assertTrue(tight.startPlaybackMs >= 6_000, "network barely above the bitrate: start with a cushion (${tight.startPlaybackMs})")
        assertEquals(150_000, tight.maxBufferMs, "poor tier: buffer as much as allowed")
        assertFalse(tight.prefetchAllowed)
        assertTrue(tight.readAheadBytes > good.readAheadBytes)
    }

    @Test
    fun memoryBudgetCapsTheRamBufferOnSmallDevices() {
        val p = BufferManager(DeviceProfile(memoryClassMb = 128, lowRam = true, cpuCores = 4))
            .update(sample(0, 10_000), net(330 * KB, NetworkQuality.POOR))
        assertEquals(12 * MB, p.targetBufferBytes)
        assertTrue(p.maxBufferMs <= 12 * MB * 1000 / bitrate, "max ${p.maxBufferMs} ms fits 12 MB")
        assertTrue(p.readAheadBytes >= 16 * MB, "the disk window compensates")
        assertTrue(p.preferFastSeek, "weak device seeks to key frames")
    }

    @Test
    fun aFullBufferDrainingWhileNotLoadingIsNoRisk() {
        val m = BufferManager()
        var p = m.update(sample(0, 50_000), net(4 * MB, NetworkQuality.GOOD))
        for (t in 500L..20_000L step 500) p = m.update(sample(t, 50_000 - t).copy(loading = false), net(4 * MB, NetworkQuality.GOOD))
        assertEquals(RebufferRisk.NONE, p.risk, "load control paused loading at max buffer: draining is expected")
    }

    @Test
    fun drainingBufferIsDetectedBeforeItRunsDry() {
        val m = BufferManager()
        var p = m.update(sample(0, 40_000), net(4 * MB, NetworkQuality.GOOD))
        assertEquals(RebufferRisk.NONE, p.risk)
        // Buffer falls 700 ms per second (network delivers 30 % of the bitrate).
        for (t in 500L..10_000L step 500) p = m.update(sample(t, 40_000 - t * 7 / 10), net(4 * MB, NetworkQuality.GOOD))
        assertEquals(RebufferRisk.ELEVATED, p.risk, p.reason)
        assertEquals(p.maxBufferMs, p.minBufferMs, "loads continuously while at risk")
        assertFalse(p.prefetchAllowed)
        for (t in 10_500L..52_000L step 500) p = m.update(sample(t, (40_000 - t * 7 / 10).coerceAtLeast(0)), net(4 * MB, NetworkQuality.GOOD))
        assertEquals(RebufferRisk.HIGH, p.risk, "under 8 s to empty")
    }

    @Test
    fun meteredNetworksReadLessAheadEspeciallyWhilePaused() {
        val wifi = BufferManager().update(sample(0, 20_000), net(4 * MB, NetworkQuality.GOOD))
        val mobile = BufferManager().update(sample(0, 20_000), net(4 * MB, NetworkQuality.GOOD, metered = true))
        val paused = BufferManager().update(sample(0, 20_000, PlaybackPhase.PAUSED), net(4 * MB, NetworkQuality.GOOD, metered = true))
        assertTrue(wifi.readAheadBytes >= mobile.readAheadBytes)
        assertTrue(paused.readAheadBytes < mobile.readAheadBytes || paused.readAheadBytes == bitrate * 50_000 / 1000 * 12 / 10)
        assertFalse(mobile.prefetchAllowed, "no prefetch on mobile data by default")
    }

    @Test
    fun stallsRaiseTheResumeThresholdAndBetterNetworksNeedToHold() {
        val m = BufferManager()
        val first = m.update(sample(0, 5_000), net(1 * MB, NetworkQuality.GOOD)).resumeAfterRebufferMs
        m.update(sample(500, 0, PlaybackPhase.BUFFERING), net(1 * MB, NetworkQuality.GOOD))
        m.update(sample(4_000, 6_000), net(1 * MB, NetworkQuality.GOOD))
        val second = m.update(sample(4_500, 0, PlaybackPhase.BUFFERING), net(1 * MB, NetworkQuality.GOOD)).resumeAfterRebufferMs
        assertTrue(second > first, "$second > $first")

        val h = BufferManager()
        h.update(sample(0, 20_000), net(100 * KB, NetworkQuality.POOR))
        val stillPoor = h.update(sample(5_000, 20_000), net(4 * MB, NetworkQuality.GOOD))
        assertEquals(150_000, stillPoor.maxBufferMs, "good news must hold for 10 s")
        val relaxed = h.update(sample(16_000, 20_000), net(4 * MB, NetworkQuality.GOOD))
        assertEquals(50_000, relaxed.maxBufferMs)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SeekManagerTest {
    private class Target : SeekTarget {
        val seeks = mutableListOf<Long>()
        var fast = false
        override fun applySeek(positionMs: Long) { seeks += positionMs }
        override fun setFastSeek(enabled: Boolean) { fast = enabled }
    }

    @Test
    fun firstSeekIsImmediateBurstsCollapseToTheLastTarget() = runTest {
        val t = Target()
        val m = SeekManager(t, backgroundScope, { testScheduler.currentTime })
        m.seekTo(10_000)
        assertEquals(listOf(10_000L), t.seeks, "leading edge: no delay")
        advanceTimeBy(20); m.seekTo(20_000)
        advanceTimeBy(20); m.seekTo(30_000)
        advanceTimeBy(20); m.seekTo(40_000)
        assertEquals(1, t.seeks.size)
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(10_000L, 40_000L), t.seeks, "intermediate targets never reach the player")
        assertEquals(2, m.stats.coalesced)
        advanceTimeBy(1_000); m.seekTo(50_000)
        assertEquals(50_000L, t.seeks.last(), "a later seek is immediate again")
    }

    @Test
    fun scrubbingPreviewsAtABoundedRateThenOneExactSeek() = runTest {
        val t = Target()
        val m = SeekManager(t, backgroundScope, { testScheduler.currentTime })
        m.beginScrub()
        assertTrue(t.fast)
        for (i in 1..30) { m.seekTo(i * 1_000L); advanceTimeBy(33) } // 1 s of dragging
        m.endScrub(31_000)
        runCurrent()
        assertTrue(t.seeks.size <= 6, "${t.seeks.size} previews for 30 drag events")
        assertEquals(31_000L, t.seeks.last())
        assertFalse(t.fast, "the final seek is exact")
    }
}
