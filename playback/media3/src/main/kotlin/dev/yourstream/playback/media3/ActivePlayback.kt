package dev.yourstream.playback.media3

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player the system's media controls drive: the notification, the lock screen, headset and
 * Bluetooth buttons, and background playback (the app's `PlaybackService` publishes it as a media
 * session). One at a time: the screen that plays registers its player, and unregisters it before
 * releasing it.
 */
@Singleton
class ActivePlayback @Inject constructor() {
    /** A registered player with what the controls show. */
    data class Entry(val player: Player, val title: String, val subtitle: String?)

    private val _current = MutableStateFlow<Entry?>(null)
    val current: StateFlow<Entry?> = _current.asStateFlow()

    /** [player] is now the one playing (or its title changed). */
    fun attach(player: Player, title: String, subtitle: String?) {
        val now = _current.value
        if (now != null && now.player === player && now.title == title && now.subtitle == subtitle) return
        _current.value = Entry(player, title, subtitle)
    }

    /** [player] is about to be released. Another player registered since is left alone. */
    fun detach(player: Player) {
        _current.compareAndSet(_current.value?.takeIf { it.player === player } ?: return, null)
    }
}

/**
 * [player] with the title and subtitle the media notification and lock screen show. The players
 * build their media items from Telegram file ids and add-on URLs, which carry no human title.
 */
@OptIn(UnstableApi::class)
class TitledPlayer(player: Player, private val title: String, private val subtitle: String?) : ForwardingPlayer(player) {
    override fun getMediaMetadata(): MediaMetadata = super.getMediaMetadata().buildUpon()
        .setTitle(title)
        .setDisplayTitle(title)
        .setArtist(subtitle)
        .setSubtitle(subtitle)
        .build()
}
