package dev.yourstream.desktop

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.playback.ByteSource
import dev.yourstream.desktop.playback.StreamServer
import dev.yourstream.desktop.playback.VideoPlayerController
import dev.yourstream.desktop.playback.VlcEngine
import dev.yourstream.desktop.ui.PlayRequest
import dev.yourstream.desktop.ui.PlayerContent
import dev.yourstream.desktop.ui.YourStreamTheme
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.testing.SimulatedTelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

/**
 * The player screen with real VLC playing the two-language test file: the picture drawn under
 * the controls, the audio menu named by language, a pick remembered. Screenshot in
 * build/screenshots/player.png. Skipped without VLC.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerScreenTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun playerWithOverlayControlsAndLanguageMenus() {
        val engine = VlcEngine(NoOpLogger)
        val status = runBlocking { engine.start() }
        assumeTrue("VLC not installed", status is VlcEngine.Status.Ready)
        val clip = File(tmp.root, "multi.mkv").also { it.writeBytes(PlayerScreenTest::class.java.getResourceAsStream("/multi-audio.mkv")!!.readBytes()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sim = SimulatedTelegramFiles(tmp.newFolder("td")).apply { addFile(9, clip) }
        val streamer = TelegramFileStreamer(sim, scope)
        val server = StreamServer(object : ByteSource {
            override suspend fun size(fileId: Int) = clip.length()
            override suspend fun open(fileId: Int, position: Long): ByteSource.Reader {
                val s = streamer.open(fileId, position)
                return object : ByteSource.Reader {
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = s.read(buffer, offset, length)
                    override fun close() = s.close()
                }
            }
        }, NoOpLogger)
        val item = MediaRecord(1, 1, 1, 9, "r9", "Sample.Movie.2024.1080p.mkv", clip.length(), video = true, title = "Sample Movie", resolution = 1080, languages = listOf("ta", "en"))
        val picked = mutableListOf<String?>()
        val controller = VideoPlayerController((status as VlcEngine.Status.Ready).factory, NoOpLogger)
        val request = PlayRequest(
            title = "Sample Movie", queue = listOf(item), urlFor = { server.urlFor(it.fileId, it.fileName) },
            resumeMs = { 0 }, savePosition = { _, _, _ -> }, onAudioPicked = { picked += it },
        )
        try {
            runDesktopComposeUiTest(1280, 720) {
                setContent { YourStreamTheme(true) { Surface(Modifier.fillMaxSize()) { PlayerContent(request, controller, false, {}, {}) } } }
                waitUntil(timeoutMillis = 20_000) { controller.state.value.audio.size == 2 && controller.state.value.timeMs > 1_000 }
                // Move the mouse: the controls show over the picture.
                onNodeWithTag("player").performMouseInput { moveTo(center) }
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Tamil")).fetchSemanticsNodes().isNotEmpty() }
                shot("player")
                onNodeWithTag("player-audio").performClick()
                onNodeWithText("English · AAC Stereo").assertExists()
                shot("player-audio-menu", popup = true)
                onNodeWithText("English · AAC Stereo").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.audio.firstOrNull { it.id == controller.state.value.audioSelected }?.language == "en" }
                assertEquals(listOf<String?>("en"), picked)
            }
        } finally {
            controller.close()
            server.close()
            sim.close()
            scope.cancel()
            engine.close()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.shot(name: String, popup: Boolean = false) {
        // An open menu is its own layer (a second root).
        val bitmap = (if (popup) onAllNodes(androidx.compose.ui.test.isRoot())[1] else onAllNodes(androidx.compose.ui.test.isRoot())[0]).captureToImage()
        val px = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        val bytes = ByteArray(px.size * 4)
        for (i in px.indices) {
            val c = px[i]
            bytes[i * 4] = (c and 0xFF).toByte(); bytes[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
            bytes[i * 4 + 2] = (c shr 16 and 0xFF).toByte(); bytes[i * 4 + 3] = (c shr 24 and 0xFF).toByte()
        }
        val b = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(bitmap.width, bitmap.height); installPixels(bytes) }
        File("build/screenshots").mkdirs()
        File("build/screenshots/$name.png").writeBytes(Image.makeFromBitmap(b).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
