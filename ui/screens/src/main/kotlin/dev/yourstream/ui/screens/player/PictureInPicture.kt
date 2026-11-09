package dev.yourstream.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import dev.yourstream.ui.screens.settings.LocalAppSettings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Picture-in-picture: while a video plays, leaving the app (home button, gesture) shrinks it into
 * a floating window instead of stopping it. The activity enters the mode; players tell it when
 * they are eligible and hide their controls while it is active.
 */
object PictureInPicture {
    /** A player is on screen and playing (and the setting is on). */
    val eligible = MutableStateFlow(false)
    /** The activity is in picture-in-picture right now. */
    val active = MutableStateFlow(false)
}

/** Marks the player as eligible while [playing]; cleared when the player leaves. */
@Composable
internal fun PipEligible(playing: Boolean) {
    val enabled = LocalAppSettings.current.pictureInPicture
    DisposableEffect(playing, enabled) {
        PictureInPicture.eligible.value = playing && enabled
        onDispose { PictureInPicture.eligible.value = false }
    }
}

@Composable
internal fun inPictureInPicture(): State<Boolean> = PictureInPicture.active.collectAsState()
