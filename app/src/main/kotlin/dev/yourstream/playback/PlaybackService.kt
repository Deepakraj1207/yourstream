package dev.yourstream.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import dev.yourstream.MainActivity
import dev.yourstream.playback.media3.ActivePlayback
import dev.yourstream.playback.media3.TitledPlayer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Publishes the player that is playing ([ActivePlayback]) as a media session: controls in the
 * notification and on the lock screen, headset and Bluetooth buttons, and a foreground service
 * while it plays, so playback continues with the screen off or the app in the background.
 *
 * Not exported, and only the system and this app may connect ([onConnect]): other apps cannot
 * read what is playing. Removing the app from the recent apps stops playback.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var active: ActivePlayback

    private val scope = MainScope()
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch { active.current.collect(::publish) }
    }

    /** A new player (or new title) replaces the session; none left: the service ends. */
    private fun publish(entry: ActivePlayback.Entry?) {
        session?.let { removeSession(it); it.release() }
        session = null
        if (entry == null) {
            stopSelf()
            return
        }
        session = MediaSession.Builder(this, TitledPlayer(entry.player, entry.title, entry.subtitle))
            .setId(SESSION_ID)
            .setSessionActivity(openApp())
            .setCallback(trustedControllersOnly)
            .build()
            .also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The user swiped the app away: stop, rather than keep playing with no way back but the notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }

    /** Tapping the notification returns to the player that is open. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The media notification, trusted system controllers (SystemUI, the lock screen, Bluetooth and
     * other holders of MEDIA_CONTENT_CONTROL) and this app; nobody else.
     */
    private val trustedControllersOnly = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (session.isMediaNotificationController(controller) || controller.isTrusted || controller.packageName == packageName) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }
    }

    private companion object {
        const val SESSION_ID = "yourstream"
    }
}
