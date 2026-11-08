package dev.yourstream.playback.media3

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.adaptive.AdaptiveStreaming
import dev.yourstream.playback.adaptive.BufferManager
import dev.yourstream.playback.adaptive.Connectivity
import dev.yourstream.playback.adaptive.DefaultNetworkMonitor
import dev.yourstream.playback.adaptive.DownloadGovernor
import dev.yourstream.playback.adaptive.NextMediaProvider
import dev.yourstream.playback.adaptive.PrefetchCandidate
import dev.yourstream.playback.adaptive.PrefetchConfig
import dev.yourstream.playback.adaptive.PrefetchManager
import dev.yourstream.playback.adaptive.PrefetchStatus
import dev.yourstream.playback.adaptive.TransferLimits
import dev.yourstream.playback.stream.StreamingConfig
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.playback.testing.TestMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowTrace
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The adaptive engine inside the real ExoPlayer (Robolectric), against simulated Telegram. */
@RunWith(RobolectricTestRunner::class)
class AdaptivePlaybackTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("adaptive-playback").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"), partSize = 128 * 1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val governor = DownloadGovernor(sim, scope, TransferLimits(maxConcurrentDownloads = 2))
    private val streamer = TelegramFileStreamer(governor, scope, StreamingConfig(stallTimeoutMs = 10_000))
    private val monitor = DefaultNetworkMonitor(MutableStateFlow(Connectivity.WIFI), MutableStateFlow(true), scope, System::currentTimeMillis)
        .also { AdaptiveStreaming.connect(streamer, it) }
    private val traces = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }
    private val controllers = mutableListOf<TelegramPlaybackController>()
    private var next: NextMediaProvider = NextMediaProvider { null }

    @Before fun noTraceRecording() = ShadowTrace.setEnabled(false)

    @After fun tearDown() {
        traces.shutdownNow(); controllers.forEach { runCatching { it.release() } }
        sim.close(); scope.cancel(); dir.deleteRecursively()
    }

    private fun controller() = TelegramPlaybackController(
        context, streamer, config = PlaybackConfig(loadRetries = 1, retryBaseDelayMs = 100),
        playerFactory = { ctx, sources, load ->
            ExoPlayer.Builder(ctx).setClock(FakeClock(true)).setMediaSourceFactory(sources).setLoadControl(load)
                .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE).setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE).setStuckSuppressedDetectionTimeoutMs(Int.MAX_VALUE).build()
                .also { p ->
                    val h = android.os.Handler(p.playbackLooper)
                    traces.scheduleWithFixedDelay({ h.post { ShadowTrace.reset() } }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                }
        },
        adaptive = { s ->
            AdaptivePlayback(AdaptiveStreaming(streamer, governor, monitor, BufferManager(), s, PrefetchManager(governor, { next.next() }, s, PrefetchConfig(pollMs = 50))))
        },
    ).also { controllers += it }

    private fun media(id: Int, file: File, durationMs: Long?) = PlayableMedia(
        file.name, file.name, id, "u$id", file.length(), durationMs, null, null, null, false, PlaybackTarget(id.toLong()), id.toLong(),
    )

    @Test
    fun playsWithTheAdaptiveLoadControlAndAppliesThePolicy() {
        val file = TestMedia.file(TestMedia.SMALL_360P, dir).also { sim.addFile(1, it) }
        sim.bandwidthBytesPerSecond = 2L * 1024 * 1024
        val c = controller()
        assertTrue(c.loadControl is AdaptiveLoadControl)
        c.play(media(1, file, 6_000))
        run(c.player).withTimeoutMs(30_000).untilState(Player.STATE_READY)
        c.sample()
        val policy = c.adaptive!!.streaming.policy
        assertTrue(policy.reason.isNotEmpty())
        assertEquals(policy.readAheadBytes.coerceAtLeast(streamer.config.minWindowBytes), streamer.stats(1)!!.readAheadBytes, "disk window follows the policy")
        run(c.player).withTimeoutMs(30_000).untilState(Player.STATE_ENDED)
    }

    @Test
    fun rapidSeeksAreCoalescedIntoTheLastTarget() {
        val long = TestMedia.longVideo()
        assumeTrue(long != null)
        sim.addFile(1, long!!)
        sim.bandwidthBytesPerSecond = 8L * 1024 * 1024
        val c = controller()
        c.play(media(1, long, TestMedia.LONG_DURATION_MS))
        run(c.player).withTimeoutMs(60_000).untilState(Player.STATE_READY)
        val before = c.metrics.value.seekCount
        listOf(600_000L, 1_200_000L, 1_800_000L, 2_400_000L, 3_000_000L).forEach { c.seekTo(it) }
        RobolectricUtil.runMainLooperUntil({ c.snapshot().positionMs in 2_998_000..3_002_000 && c.player.playbackState == Player.STATE_READY }, 60_000, androidx.media3.common.util.Clock.DEFAULT)
        val applied = c.metrics.value.seekCount - before
        assertTrue(applied <= 2, "5 rapid seeks reached the player as $applied")
        assertTrue(abs(c.snapshot().positionMs - 3_000_000) < 2_000)
    }

    @Test
    fun nextEpisodeHeadIsPrefetchedNearTheEndWithinTheDownloadLimit() {
        val long = TestMedia.longVideo()
        assumeTrue(long != null)
        sim.addFile(1, long!!)
        val nextFile = TestMedia.file(TestMedia.RES_720P_MOOV_END, dir).let { small ->
            // A larger stand-in for the next episode (content irrelevant: only its head/tail are fetched).
            File(dir, "next.bin").also { f -> java.io.RandomAccessFile(f, "rw").use { it.setLength(150L * 1024 * 1024) }; small.length() }
        }
        sim.addFile(2, nextFile)
        next = NextMediaProvider { PrefetchCandidate(2, nextFile.length(), TestMedia.LONG_DURATION_MS, "E02") }
        sim.bandwidthBytesPerSecond = 8L * 1024 * 1024
        val c = controller()
        c.play(media(1, long, TestMedia.LONG_DURATION_MS), startPositionMs = TestMedia.LONG_DURATION_MS - 100_000)
        run(c.player).withTimeoutMs(60_000).untilState(Player.STATE_READY)
        // Media time runs on a fake clock far faster than the (real-time) network, so "playing" would
        // stall constantly and the policy would rightly refuse prefetch. Paused near the end is a
        // legitimate prefetch moment and keeps the test about the prefetch logic.
        c.pause()
        val prefetch = c.adaptive!!.prefetch!!
        val deadline = System.currentTimeMillis() + 30_000
        while (prefetch.state.status != PrefetchStatus.DONE && System.currentTimeMillis() < deadline) {
            c.sample()
            // The prefetcher runs on the main looper: let Robolectric's virtual main-thread time pass.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        assertEquals(PrefetchStatus.DONE, prefetch.state.status, prefetch.state.toString() + " policy=" + c.adaptive!!.streaming.policy)
        val f = sim.file(2)
        assertTrue(f.prefix(0) >= 2L * 1024 * 1024, "head of the next episode is on the device")
        assertTrue(f.downloadedFraction() < 0.15, "but not the whole episode (${(f.downloadedFraction() * 100).toInt()}%)")
        assertTrue(governor.stats.value.peakConcurrent <= 2, "download limit held")
    }
}
