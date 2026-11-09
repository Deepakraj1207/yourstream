package dev.yourstream.playback.media3

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.stream.StreamingConfig
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.playback.testing.TestMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.robolectric.shadows.ShadowTrace
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End to end: real ExoPlayer (Robolectric, shadow codecs) → TelegramMediaSourceFactory →
 * TelegramMediaDataSource → TelegramFileStreamer → simulated Telegram with real-time bandwidth,
 * latency and outages. Media playback time runs on Media3's fake clock (fast); network time is
 * real, so the measured startup/seek numbers are dominated by the simulated network + stack.
 */
@RunWith(RobolectricTestRunner::class)
class TelegramPlaybackTest {

    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("playback-test").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"), partSize = 128 * 1024)
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controllers = mutableListOf<TelegramPlaybackController>()
    private val saved = CopyOnWriteArrayList<Pair<Long, Long>>()
    private val mb = 1024L * 1024L

    /**
     * Robolectric records every android.os.Trace section in an unbounded per-thread queue; ExoPlayer
     * traces each playback-loop iteration, and with the fake clock spinning while the simulated
     * network works in real time that exhausts the heap on long files. Not needed here.
     */
    @Before fun disableTraceRecording() = ShadowTrace.setEnabled(false)

    // setEnabled does not stop endSection from recording; clear the queue on the playback thread.
    private val traceJanitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }

    private fun clearTracesPeriodically(player: ExoPlayer) {
        val handler = android.os.Handler(player.playbackLooper)
        traceJanitor.scheduleWithFixedDelay({ handler.post { ShadowTrace.reset() } }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @After fun tearDown() {
        traceJanitor.shutdownNow()
        controllers.forEach { runCatching { it.release() } }
        sim.close(); streamScope.cancel(); dir.deleteRecursively()
    }

    private val streamers = mutableListOf<TelegramFileStreamer>()

    private fun streamer(stallMs: Long = 10_000) = TelegramFileStreamer(
        sim, streamScope, StreamingConfig(defaultReadAheadBytes = 4 * mb, minReadAheadBytes = 2 * mb, maxReadAheadBytes = 8 * mb, stallTimeoutMs = stallMs),
    ).also { streamers += it }

    private fun controller(
        streamer: TelegramFileStreamer = streamer(),
        // Automatic recovery off: these tests exercise the manual paths; recovery has its own tests.
        config: PlaybackConfig = PlaybackConfig(loadRetries = 1, retryBaseDelayMs = 100, retryMaxDelayMs = 200, recovery = null),
        resolver: MediaSourceResolver? = null,
        network: MutableStateFlow<Boolean>? = null,
    ) = TelegramPlaybackController(
        context, streamer, resolver, config,
        progressSink = { _, _, position, duration -> saved += position to duration },
        networkAvailable = network,
        // Media time on Media3's auto-advancing fake clock; stuck detection off, because fake time
        // races ahead while the simulated network waits in real time.
        playerFactory = { ctx, sources, load ->
            ExoPlayer.Builder(ctx).setClock(FakeClock(true)).setMediaSourceFactory(sources).setLoadControl(load)
                .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE).setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE).setStuckSuppressedDetectionTimeoutMs(Int.MAX_VALUE).build()
                .also(::clearTracesPeriodically)
                .also { player ->
                    player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                        override fun onLoadError(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                            error: java.io.IOException,
                            wasCanceled: Boolean,
                        ) { println("LOAD ERROR (retried) at ${nowMs()}: ${error.telegramCause() ?: error}") }
                    })
                }
                .also { player ->
                    // Robolectric's AudioTrack never drains on long content, so the audio renderer would
                    // never be ready. Audio samples are still downloaded and demuxed (same byte traffic);
                    // only rendering is off.
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
                }
        },
    ).also { controllers += it }

    private fun media(id: Int, file: File, durationMs: Long? = null) = PlayableMedia(
        title = file.name, fileName = file.name, fileId = id, remoteUniqueId = "u$id", sizeBytes = file.length(),
        durationMs = durationMs, mimeType = null, width = null, height = null, alreadyDownloaded = false,
        target = PlaybackTarget(itemId = id.toLong()), mediaId = id.toLong(),
    )

    private fun add(id: Int, name: String): File = TestMedia.file(name, dir).also { sim.addFile(id, it) }

    private fun nowMs() = System.nanoTime() / 1_000_000

    private fun metric(name: String, value: Any) {
        val line = "METRIC playback.$name = $value"
        println(line)
        File("build/reports").mkdirs()
        File("build/reports/playback-metrics.txt").appendText(line + "\n")
    }

    /** [retriedErrorsExpected]: outage tests, where stalls are retried as non-fatal load errors on purpose. */
    private fun Player.awaitState(state: Int, timeoutMs: Long = 30_000, retriedErrorsExpected: Boolean = false) = try {
        run(this as ExoPlayer).withTimeoutMs(timeoutMs).let { if (retriedErrorsExpected) it.ignoringNonFatalErrors() else it }.untilState(state)
    } catch (e: java.util.concurrent.TimeoutException) {
        println("TIMEOUT waiting for state $state: player state=$playbackState pos=$currentPosition buffered=$bufferedPosition loading=$isLoading")
        controllers.forEach { println("  controller: ${it.snapshot()} ${it.metrics.value}") }
        streamers.forEach { st -> (1..20).forEach { id -> st.stats(id)?.let { println("  stream: $it") } } }
        println("  sim calls: ${sim.downloadCalls.takeLast(20)}")
        throw e
    }

    @Test
    fun smallMp4PlaysToTheEndWithDurationPositionAndCompletion() {
        val file = add(1, TestMedia.SMALL_360P)
        sim.bandwidthBytesPerSecond = 4 * mb
        sim.latencyMs = 40
        val c = controller()
        c.play(media(1, file))
        assertEquals(PlaybackStatus.PREPARING, c.snapshot().status)
        c.player.awaitState(Player.STATE_READY)
        val ready = c.snapshot()
        assertEquals(PlaybackStatus.READY, ready.status)
        assertTrue(abs(ready.durationMs!! - 6_000) < 150, "duration ${ready.durationMs}")
        assertEquals(640, ready.videoWidth)
        assertEquals(360, ready.videoHeight)
        // The decoded formats, for the player's quality info.
        assertTrue(ready.format?.videoMimeType?.startsWith("video/") == true, "video format ${ready.format}")
        assertEquals(1f, ready.speed)
        c.player.awaitState(Player.STATE_ENDED)
        val end = c.snapshot()
        assertEquals(PlaybackStatus.ENDED, end.status)
        assertTrue(end.positionMs >= 5_900)
        c.setSpeed(1.5f)
        assertEquals(1.5f, c.snapshot().speed, "speed is reported back in the state")
        c.sample()
        val m = c.metrics.value
        assertNotNull(m.startupMs)
        assertEquals(file.length(), m.bytesServed, "every byte read exactly once")
        // Saves run on a background scope (no ordering guarantee): one of them records completion.
        RobolectricUtil.runMainLooperUntil { saved.any { it.first == it.second && it.second > 0 } }
        assertTrue(saved.any { it.first == it.second && it.second > 0 }, "completion saved as position == duration: $saved")
        metric("small360p.startupMs", m.startupMs!!)
        metric("small360p.firstFrameMs", m.firstFrameMs ?: -1)
        metric("small360p.peakJavaHeapMB", m.peakJavaHeapBytes / mb)
    }

    @Test
    fun differentResolutionsAndContainersIncludingMoovAtTheEnd() {
        val cases = listOf(
            Triple(TestMedia.SMALL_360P, 640, 360),
            Triple(TestMedia.RES_480P_MKV, 854, 480),
            Triple(TestMedia.RES_720P_MOOV_END, 1280, 720),
            Triple(TestMedia.RES_1080P, 1920, 1080),
        )
        sim.bandwidthBytesPerSecond = 2 * mb
        sim.latencyMs = 30
        cases.forEachIndexed { i, (name, w, h) ->
            val id = 10 + i
            val file = add(id, name)
            val c = controller()
            c.play(media(id, file))
            c.player.awaitState(Player.STATE_READY)
            val s = c.snapshot()
            assertEquals(w, s.videoWidth, name)
            assertEquals(h, s.videoHeight, name)
            c.player.awaitState(Player.STATE_ENDED)
            c.sample()
            metric("resolution.${name.substringBefore('.')}.startupMs", c.metrics.value.startupMs!!)
            if (name == TestMedia.RES_720P_MOOV_END) {
                // Head, then a jump to the moov at the end, then back to the samples.
                assertTrue(streamers.last().stats(id)!!.streamsOpened >= 2, "moov at the end: the player re-opens at the end, then at the samples")
            }
            c.release()
        }
    }

    @Test
    fun largeVideoSeekFrom0005To012000DownloadsOnlyAroundTheTarget() {
        val long = TestMedia.longVideo()
        assumeTrue("ffmpeg not available to generate the long test video", long != null)
        sim.addFile(1, long!!)
        sim.bandwidthBytesPerSecond = 8 * mb // ~64 Mbit/s
        sim.latencyMs = 40
        val c = controller()
        val started = nowMs()
        c.play(media(1, long, TestMedia.LONG_DURATION_MS))
        c.player.awaitState(Player.STATE_READY, 60_000)
        val startup = nowMs() - started
        assertTrue(abs(c.snapshot().durationMs!! - TestMedia.LONG_DURATION_MS) < 2_000)

        c.seekTo(5_000)
        c.player.awaitState(Player.STATE_READY, 60_000)
        val before = sim.file(1).downloadedFraction()
        c.seekTo(4_800_000) // 01:20:00
        c.player.awaitState(Player.STATE_READY, 60_000)
        val position = c.snapshot().positionMs
        assertTrue(abs(position - 4_800_000) < 2_000, "position $position")
        run(c.player).withTimeoutMs(60_000).untilPositionAtLeast(4_803_000)

        val f = sim.file(1)
        val fraction = f.downloadedFraction()
        val middle = (long.length() * 0.2 / sim.partSize).toInt() until (long.length() * 0.85 / sim.partSize).toInt()
        assertEquals(0, f.downloadedParts.count { it in middle }, "nothing between 20% and 85% of the file was downloaded")
        assertTrue(fraction < 0.10, "downloaded ${"%.1f".format(fraction * 100)}% of ${long.length() / mb} MB")
        c.sample()
        val m = c.metrics.value
        metric("long85min.sizeMB", long.length() / mb)
        metric("long85min.startupMs", startup)
        metric("long85min.seek0005.latencyMs", m.seekLatenciesMs[0])
        metric("long85min.seek012000.latencyMs", m.seekLatenciesMs[1])
        metric("long85min.downloadedPercentBeforeFarSeek", "%.2f".format(before * 100))
        metric("long85min.downloadedPercentAfter", "%.2f".format(fraction * 100))
        metric("long85min.peakJavaHeapMB", m.peakJavaHeapBytes / mb)
    }

    @Test
    fun repeatedSeekingEndsReadyAtTheLastTarget() {
        val long = TestMedia.longVideo()
        assumeTrue(long != null)
        sim.addFile(1, long!!)
        sim.bandwidthBytesPerSecond = 8 * mb
        sim.latencyMs = 30
        val c = controller()
        c.play(media(1, long, TestMedia.LONG_DURATION_MS))
        c.player.awaitState(Player.STATE_READY, 60_000)
        val rnd = Random(5)
        // Settled seeks.
        repeat(8) {
            c.seekTo(rnd.nextLong(0, TestMedia.LONG_DURATION_MS - 10_000))
            c.player.awaitState(Player.STATE_READY, 60_000)
        }
        // Scrubbing: five seeks without waiting, then the final one.
        repeat(5) { c.seekTo(rnd.nextLong(0, TestMedia.LONG_DURATION_MS - 10_000)) }
        c.seekTo(3_000_000)
        c.player.awaitState(Player.STATE_READY, 60_000)
        assertTrue(abs(c.snapshot().positionMs - 3_000_000) < 2_000)
        c.sample()
        val m = c.metrics.value
        val settled = m.seekLatenciesMs.take(8).sorted()
        assertTrue(sim.file(1).downloadedFraction() < 0.25, "downloaded ${(sim.file(1).downloadedFraction() * 100).toInt()}%")
        metric("repeatedSeek.medianLatencyMs", settled[settled.size / 2])
        metric("repeatedSeek.maxLatencyMs", settled.last())
        metric("repeatedSeek.afterScrubLatencyMs", m.lastSeekLatencyMs!!)
        metric("repeatedSeek.downloadedPercent", "%.1f".format(sim.file(1).downloadedFraction() * 100))
    }

    @Test
    fun pauseHoldsThePositionAndResumeContinues() {
        val file = add(1, TestMedia.SMALL_360P)
        val c = controller()
        c.play(media(1, file))
        run(c.player).withTimeoutMs(30_000).untilPositionAtLeast(2_000)
        c.pause()
        run(c.player).untilPlayWhenReadyIs(false)
        val held = c.snapshot().positionMs
        assertTrue(!c.snapshot().isPlaying)
        RobolectricUtil.runMainLooperUntil { false.also { Thread.sleep(5) } || c.snapshot().positionMs != held || System.nanoTime() % 1 == 0L }
        assertEquals(held, c.snapshot().positionMs, "position stays while paused")
        c.resume()
        c.player.awaitState(Player.STATE_ENDED)
        assertTrue(saved.any { it.first in (held - 100)..(held + 100) }, "progress saved on pause")
    }

    @Test
    fun shortNetworkInterruptionIsBridgedByBuffering() {
        val file = add(1, TestMedia.SMALL_360P)
        sim.bandwidthBytesPerSecond = 96 * 1024 // file takes ~3 s
        val c = controller()
        c.play(media(1, file))
        c.player.awaitState(Player.STATE_READY)
        sim.online = false
        Thread.sleep(1_500)
        sim.online = true
        c.player.awaitState(Player.STATE_ENDED)
        assertEquals(null, c.snapshot().error)
        c.sample()
        metric("shortInterruption.rebufferCount", c.metrics.value.rebufferCount)
        metric("shortInterruption.dataWaitMs", c.metrics.value.dataWaitMs)
    }

    @Test
    fun longNetworkInterruptionErrorsAndRetryResumesWhereItStopped() {
        sim.partSize = 16 * 1024 // a part arrives every 0.25 s, well inside the 1.5 s stall timeout
        val file = add(1, TestMedia.SMALL_360P)
        sim.bandwidthBytesPerSecond = 64 * 1024
        val c = controller(streamer(stallMs = 1_500))
        c.play(media(1, file))
        c.player.awaitState(Player.STATE_READY)
        sim.online = false
        run(c.player).withTimeoutMs(30_000).ignoringNonFatalErrors().untilPlayerError()
        val error = c.snapshot().error!!
        assertEquals(PlaybackErrorKind.NETWORK, error.kind)
        assertTrue(error.retryable)
        // (With the fake media clock the reported position can run past the end while starved; clamp.)
        val position = minOf(c.snapshot().positionMs, c.snapshot().durationMs ?: Long.MAX_VALUE)
        sim.online = true
        val started = nowMs()
        c.retry()
        c.player.awaitState(Player.STATE_READY, retriedErrorsExpected = true)
        assertTrue(c.snapshot().positionMs >= position - 100, "resumes at ${c.snapshot().positionMs}, stopped at $position")
        c.player.awaitState(Player.STATE_ENDED, retriedErrorsExpected = true)
        assertEquals(1, c.metrics.value.retries)
        metric("retryAfterOutage.recoveryMs", nowMs() - started)
    }

    @Test
    fun reconnectRetriesAutomatically() {
        sim.partSize = 16 * 1024 // a part arrives every 0.25 s, well inside the 1.5 s stall timeout
        val file = add(1, TestMedia.SMALL_360P)
        sim.bandwidthBytesPerSecond = 64 * 1024
        val network = MutableStateFlow(true)
        val c = controller(streamer(stallMs = 1_500), network = network)
        c.play(media(1, file))
        c.player.awaitState(Player.STATE_READY)
        sim.online = false; network.value = false
        run(c.player).withTimeoutMs(30_000).ignoringNonFatalErrors().untilPlayerError()
        sim.online = true; network.value = true
        c.player.awaitState(Player.STATE_ENDED, retriedErrorsExpected = true)
        assertEquals(1, c.metrics.value.retries)
    }

    @Test
    fun slowNetworkStartsLaterButPlaysThrough() {
        val file = add(1, TestMedia.RES_720P_MOOV_END)
        sim.bandwidthBytesPerSecond = 128 * 1024 // ~1 Mbit/s
        sim.latencyMs = 250
        val c = controller()
        val started = nowMs()
        c.play(media(1, file))
        c.player.awaitState(Player.STATE_READY)
        val startup = nowMs() - started
        c.player.awaitState(Player.STATE_ENDED)
        c.sample()
        metric("slowNetwork720pMoovEnd.startupMs", startup)
        metric("slowNetwork720pMoovEnd.rebufferCount", c.metrics.value.rebufferCount)
        metric("slowNetwork720pMoovEnd.dataWaitMs", c.metrics.value.dataWaitMs)
    }

    @Test
    fun unavailableFileIsANonRetryableError() {
        val file = add(1, TestMedia.SMALL_360P)
        sim.makeUnavailable(1)
        val c = controller()
        c.play(media(1, file))
        run(c.player).withTimeoutMs(30_000).untilPlayerError()
        val e = c.snapshot().error!!
        assertEquals(PlaybackErrorKind.FILE_UNAVAILABLE, e.kind)
        assertTrue(!e.retryable)

        // Resolution failures (deleted message) never reach the player.
        val failing = controller(resolver = object : MediaSourceResolver {
            override suspend fun resolve(target: PlaybackTarget, mediaId: Long?) = AppResult.Err(AppError.NotFound("The Telegram message no longer exists"))
        })
        val r = runBlocking { failing.open(PlaybackTarget(1)) }
        assertTrue(r is AppResult.Err)
        assertEquals(PlaybackStatus.ERROR, failing.state.value.status)
        assertEquals(PlaybackErrorKind.FILE_UNAVAILABLE, failing.state.value.error!!.kind)
    }

    @Test
    fun fileThatDisappearsSwitchesToAnotherVersion() {
        val first = add(1, TestMedia.SMALL_360P)
        val second = add(2, TestMedia.SMALL_360P)
        sim.makeUnavailable(1) // message deleted after indexing
        val asked = mutableListOf<Set<Long>>()
        val resolver = object : MediaSourceResolver {
            override suspend fun resolve(target: PlaybackTarget, mediaId: Long?) = AppResult.Ok(media(1, first))
            override suspend fun resolveExcluding(target: PlaybackTarget, excludedMediaIds: Set<Long>): AppResult<PlayableMedia> {
                asked += excludedMediaIds
                return if (2L in excludedMediaIds) AppResult.Err(AppError.NotFound("none left"))
                else AppResult.Ok(media(2, second).copy(target = PlaybackTarget(1), mediaId = 2))
            }
        }
        val c = controller(resolver = resolver, config = PlaybackConfig(loadRetries = 1, retryBaseDelayMs = 100, retryMaxDelayMs = 200))
        c.play(media(1, first).copy(target = PlaybackTarget(1)))
        val deadline = nowMs() + 30_000
        while (c.snapshot().media?.fileId != 2 && nowMs() < deadline) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        c.player.awaitState(Player.STATE_ENDED, retriedErrorsExpected = true)
        assertEquals(2, c.snapshot().media!!.fileId, "continued with the other version")
        assertEquals(listOf(setOf(1L)), asked)
        assertTrue(c.recoveryLog.value.first().contains("SwitchVersion"))
        assertEquals(null, c.snapshot().error)
    }

    @Test
    fun networkErrorsAreRetriedAutomaticallyWithBackoff() {
        sim.partSize = 16 * 1024
        val file = add(1, TestMedia.SMALL_360P)
        sim.bandwidthBytesPerSecond = 64 * 1024
        val c = controller(
            streamer(stallMs = 1_500),
            config = PlaybackConfig(loadRetries = 1, retryBaseDelayMs = 100, retryMaxDelayMs = 200, recovery = PlaybackRecoveryPolicy(baseDelayMs = 1_000)),
        )
        c.play(media(1, file))
        c.player.awaitState(Player.STATE_READY)
        sim.online = false
        run(c.player).withTimeoutMs(30_000).ignoringNonFatalErrors().untilPlayerError()
        assertTrue(c.snapshot().recovery!!.startsWith("retry 1"), c.snapshot().recovery)
        sim.online = true // back before the retry fires: no manual retry needed
        c.player.awaitState(Player.STATE_ENDED, retriedErrorsExpected = true)
        assertTrue(c.metrics.value.retries >= 1)
        assertEquals(null, c.snapshot().recovery)
    }

    @Test
    fun releaseCancelsTheTelegramDownload() {
        val long = TestMedia.longVideo()
        val file = long ?: add(2, TestMedia.SMALL_360P)
        if (long != null) sim.addFile(1, long)
        val id = if (long != null) 1 else 2
        sim.bandwidthBytesPerSecond = 2 * mb
        val c = controller()
        c.play(media(id, file))
        c.player.awaitState(Player.STATE_READY, 60_000)
        c.release()
        val deadline = System.currentTimeMillis() + 5_000
        while (id !in sim.cancelCalls && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(id in sim.cancelCalls, "download cancelled on release")
    }
}
