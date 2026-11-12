package dev.yourstream.playback.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the media notification and lock screen get: the registered player, its title, and control
 * of it, through a real Media3 session and controller (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class ActivePlaybackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val players = mutableListOf<ExoPlayer>()
    private fun player() = ExoPlayer.Builder(context).build().also { players += it }

    @After fun release() = players.forEach { it.release() }

    @Test
    fun oneRegisteredPlayerAtATimeAndAStaleDetachDoesNotDropTheNewOne() {
        val active = ActivePlayback()
        val first = player(); val second = player()
        active.attach(first, "Vikram", null)
        active.attach(second, "Leo", "2023")
        assertSame(second, active.current.value?.player)
        active.detach(first) // the old screen closing late
        assertSame(second, active.current.value?.player, "a newer player stays registered")
        active.detach(second)
        assertNull(active.current.value)
    }

    @Test
    fun theSessionShowsTheTitleAndControlsThePlayer() {
        val exo = player().apply {
            // A media item like the players build: a URI and no human title.
            setMediaItem(MediaItem.fromUri("tg://file/42"))
        }
        val session = MediaSession.Builder(context, TitledPlayer(exo, "Ben 10", "S01E06 · Washington B.C.")).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        while (!future.isDone) shadowOf(Looper.getMainLooper()).idle()
        val controller = future.get()
        try {
            assertEquals("Ben 10", controller.mediaMetadata.title.toString())
            assertEquals("S01E06 · Washington B.C.", controller.mediaMetadata.artist.toString())

            // Play/pause from the notification or a headset reach the app's player.
            controller.play()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(exo.playWhenReady)
            controller.pause()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, exo.playWhenReady)
            assertTrue(controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        } finally {
            controller.release()
            session.release()
        }
    }
}
