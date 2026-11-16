package dev.yourstream.playback.media3

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.stream.StreamingConfig
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.playback.testing.TestMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowTrace
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A movie cut into raw pieces (".001", ".002", …) plays as one file: real ExoPlayer (Robolectric)
 * → TelegramMediaDataSource → TelegramFileStreamer → simulated Telegram, with the committed test
 * clips cut into pieces the way 7-Zip / HJSplit cut them (byte ranges, no headers).
 */
@RunWith(RobolectricTestRunner::class)
class SplitPlaybackTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("split-test").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"), partSize = 16 * 1024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controllers = mutableListOf<TelegramPlaybackController>()
    private val mb = 1024L * 1024L
    private val streamer = TelegramFileStreamer(
        sim, scope, StreamingConfig(defaultReadAheadBytes = 4 * mb, minReadAheadBytes = 2 * mb, maxReadAheadBytes = 8 * mb, stallTimeoutMs = 10_000),
    )

    @Before fun noTraces() = ShadowTrace.setEnabled(false)

    @After fun tearDown() {
        controllers.forEach { runCatching { it.release() } }
        sim.close(); scope.cancel(); dir.deleteRecursively()
    }

    /** Cuts [clip] into [count] raw pieces (the last one shorter), each a simulated Telegram file from [firstId] on. */
    private fun cut(clip: String, count: Int, firstId: Int): Pair<File, List<StreamPart>> {
        val whole = TestMedia.file(clip, dir)
        val bytes = whole.readBytes()
        val size = (bytes.size + count - 1) / count
        val parts = (0 until count).map { i ->
            val piece = File(dir, "%s.%03d".format(clip, i + 1))
            piece.writeBytes(bytes.copyOfRange(i * size, minOf(bytes.size, (i + 1) * size)))
            sim.addFile(firstId + i, piece)
            StreamPart(firstId + i, piece.length(), "u${firstId + i}")
        }
        return whole to parts
    }

    private fun media(name: String, parts: List<StreamPart>) = PlayableMedia(
        title = name, fileName = name, fileId = parts.first().fileId, remoteUniqueId = parts.first().remoteUniqueId,
        sizeBytes = parts.sumOf { it.sizeBytes }, durationMs = null, mimeType = null, width = null, height = null,
        alreadyDownloaded = false, target = PlaybackTarget(itemId = 1), mediaId = 1, parts = parts,
    )

    private fun controller() = TelegramPlaybackController(
        context, streamer, null, PlaybackConfig(loadRetries = 1, retryBaseDelayMs = 100, retryMaxDelayMs = 200, recovery = null),
        progressSink = null, networkAvailable = null,
        playerFactory = { ctx, sources, load ->
            ExoPlayer.Builder(ctx).setClock(FakeClock(true)).setMediaSourceFactory(sources).setLoadControl(load)
                .setStuckBufferingDetectionTimeoutMs(Int.MAX_VALUE).setStuckPlayingDetectionTimeoutMs(Int.MAX_VALUE)
                .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE).setStuckSuppressedDetectionTimeoutMs(Int.MAX_VALUE).build()
                .also { player ->
                    // Robolectric's AudioTrack never drains; audio is still downloaded and demuxed.
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                }
        },
    ).also { controllers += it }

    private fun Player.await(state: Int) = run(this as ExoPlayer).withTimeoutMs(30_000).untilState(state)

    @Test
    fun theDataSourceReadsThePiecesAsTheOriginalFileFromAnyPosition() {
        val (whole, parts) = cut(TestMedia.RES_480P_MKV, 3, firstId = 10)
        val original = whole.readBytes()
        val uri = TelegramMediaUri.split(parts.map { it.fileId to it.sizeBytes }).toUri()
        // From the start, from inside piece 2, exactly on the piece 2→3 boundary, and a bounded range across a boundary.
        val boundary = parts[0].sizeBytes + parts[1].sizeBytes
        for ((position, length) in listOf(0L to C.LENGTH_UNSET.toLong(), parts[0].sizeBytes + 77 to C.LENGTH_UNSET.toLong(),
            boundary to C.LENGTH_UNSET.toLong(), boundary - 1000 to 3000L)) {
            val source = TelegramMediaDataSource(streamer)
            val open = source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
            val expectedLength = if (length == C.LENGTH_UNSET.toLong()) original.size - position else length
            assertEquals(expectedLength, open, "length from $position")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(7_000)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            source.close()
            assertContentEquals(original.copyOfRange(position.toInt(), (position + expectedLength).toInt()), out.toByteArray(), "bytes from $position")
        }
    }

    @Test
    fun anMkvInThreePiecesPlaysToTheEndAsOneFile() {
        val (_, parts) = cut(TestMedia.RES_480P_MKV, 3, firstId = 20)
        val c = controller()
        c.play(media("res_480p.mkv", parts))
        c.player.await(Player.STATE_READY)
        val ready = c.snapshot()
        assertTrue(abs(ready.durationMs!! - 3_000) < 150, "the whole movie's duration: ${ready.durationMs}")
        assertEquals(854, ready.videoWidth)
        assertEquals(480, ready.videoHeight)
        c.player.await(Player.STATE_ENDED)
        assertTrue(c.snapshot().positionMs >= 2_900, "played to the end: ${c.snapshot().positionMs}")
        for (p in parts) assertTrue((streamer.stats(p.fileId)?.bytesServed ?: 0) > 0, "piece ${p.fileId} was read")
    }

    @Test
    fun anMp4InFourPiecesSeeksIntoALaterPiece() {
        val (_, parts) = cut(TestMedia.SMALL_360P, 4, firstId = 30)
        val c = controller()
        c.play(media("small.mp4", parts), playWhenReady = false)
        c.player.await(Player.STATE_READY)
        assertTrue(abs(c.snapshot().durationMs!! - 6_000) < 150, "duration ${c.snapshot().durationMs}")
        c.seekTo(4_500)
        c.player.playWhenReady = true
        c.player.await(Player.STATE_READY)
        assertTrue(c.snapshot().positionMs >= 4_400, "position after the seek: ${c.snapshot().positionMs}")
        c.player.await(Player.STATE_ENDED)
        assertEquals(PlaybackStatus.ENDED, c.snapshot().status)
    }

    @Test
    fun layoutAndAddress() {
        val l = SplitLayout(listOf(100, 100, 40))
        assertEquals(240, l.total)
        assertEquals(0 to 0L, l.locate(0))
        assertEquals(0 to 99L, l.locate(99))
        assertEquals(1 to 0L, l.locate(100), "a boundary belongs to the next piece")
        assertEquals(2 to 39L, l.locate(239))
        assertEquals(2 to 40L, l.locate(240), "the very end")
        val uri = TelegramMediaUri.split(listOf(5 to 100L, 6 to 100L, 7 to 40L), durationMs = 9_000).toUri()
        val back = TelegramMediaUri.parse(uri)!!
        assertEquals(listOf(5 to 100L, 6 to 100L, 7 to 40L), back.parts)
        assertEquals(5, back.fileId)
        assertEquals(240L, back.hints.sizeBytes)
        assertEquals(9_000L, back.hints.durationMs)
        assertEquals(TelegramMediaUri(3, dev.yourstream.playback.stream.StreamHints(10, null)), TelegramMediaUri.parse(TelegramMediaUri(3, dev.yourstream.playback.stream.StreamHints(10, null)).toUri()), "single files unchanged")
    }
}
