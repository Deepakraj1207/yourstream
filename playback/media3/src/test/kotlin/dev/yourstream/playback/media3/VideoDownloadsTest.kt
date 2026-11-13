package dev.yourstream.playback.media3

import androidx.test.core.app.ApplicationProvider
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import dev.yourstream.playback.testing.TestMedia
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Offline downloads against the simulated Telegram file layer (real download loop, bandwidth,
 * cancel and delete; not TDLib itself).
 */
@RunWith(RobolectricTestRunner::class)
class VideoDownloadsTest {
    private val dir: File = Files.createTempDirectory("downloads").toFile()
    private val sim = SimulatedTelegramFiles(File(dir, "tdlib"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var local = false

    private val resolver = object : MediaSourceResolver {
        override suspend fun resolve(target: PlaybackTarget, mediaId: Long?): AppResult<PlayableMedia> {
            val f = (sim.getFile(42) as AppResult.Ok).value
            return AppResult.Ok(
                PlayableMedia("Vikram", "Vikram.mkv", if (local) 0 else 42, f.remoteUniqueId, f.size, null, null, null, null, f.isDownloadingCompleted,
                    target = target, mediaId = mediaId, localUri = if (local) "content://x/1" else null),
            )
        }
    }
    private val downloads = VideoDownloads(context, resolver, sim, scope)

    @After fun tearDown() { scope.cancel(); sim.close(); dir.deleteRecursively() }

    private suspend fun file(): TelegramFile = (sim.getFile(42) as AppResult.Ok).value

    @Test
    fun downloadsTheWholeFileShowingProgressAndKeepsIt(): Unit = runBlocking {
        val source = TestMedia.file(TestMedia.SMALL_360P, dir)
        sim.addFile(42, source)
        sim.bandwidthBytesPerSecond = maxOf(50_000L, source.length() / 4)
        assertIs<AppResult.Ok<Unit>>(downloads.start(PlaybackTarget(7), mediaId = 3))
        assertTrue(downloads.isKept(file().remoteUniqueId), "the stream cache will leave it alone")
        assertEquals(0L to 0L, sim.downloadCalls.last().second, "the whole file (offset 0, no limit)")
        withTimeout(30_000) { downloads.progress.first { it.containsKey(3L) } }
        // Done: the progress entry goes away and the file is complete.
        withTimeout(30_000) { downloads.progress.first { it.isEmpty() } }
        assertTrue(file().isDownloadingCompleted)
    }

    @Test
    fun cancelAndRemoveForgetTheFile(): Unit = runBlocking {
        val source = TestMedia.file(TestMedia.SMALL_360P, dir)
        sim.addFile(42, source)
        sim.bandwidthBytesPerSecond = 10_000 // slow enough to cancel midway
        downloads.start(PlaybackTarget(7), 3)
        downloads.cancel(PlaybackTarget(7), 3)
        assertEquals(listOf(42), sim.cancelCalls.toList())
        assertFalse(downloads.isKept(file().remoteUniqueId))
        assertTrue(downloads.progress.value.isEmpty())

        downloads.start(PlaybackTarget(7), 3)
        downloads.remove(PlaybackTarget(7), 3)
        assertEquals(listOf(42), sim.deleteCalls.toList(), "the local copy is deleted")
        assertFalse(downloads.isKept(file().remoteUniqueId))
    }

    @Test
    fun aFileAlreadyOnTheDeviceIsNotDownloaded(): Unit = runBlocking {
        sim.addFile(42, TestMedia.file(TestMedia.SMALL_360P, dir), alreadyDownloaded = true)
        downloads.start(PlaybackTarget(7), 3)
        assertTrue(sim.downloadCalls.isEmpty())
        local = true
        assertIs<AppResult.Err>(downloads.start(PlaybackTarget(7), 3), "a local-folder file has nothing to download")
    }
}
