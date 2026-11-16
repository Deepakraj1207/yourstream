package dev.yourstream.ui.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * What a touch should feel like. One place decides which vibration each kind gets, so the whole
 * app feels the same: a light tick for choosing (tabs, chips, slides), a firmer one for doing
 * something (play, apply), on/off for switches.
 */
enum class Haptic {
    /** Choosing one of several: a tab, a chip, a carousel slide, a season. */
    SELECT,
    /** A plain tap on a control that acts: play/pause, seek ±10 s, a stream. */
    TAP,
    /** Something was done: Play from a title, filters applied, a title added. */
    CONFIRM,
    /** It could not be done. */
    REJECT,
    TOGGLE_ON,
    TOGGLE_OFF,
    /** A long press opened a menu. */
    LONG_PRESS,
    /** A gesture crossed its threshold: double-tap seek, swipe seek starts, the lock. */
    THRESHOLD,
    /** Fine steps while dragging: brightness and volume, scrubbing. */
    STEP,
}

/** Off in Settings → Accessibility → Haptic feedback; the phone's own touch-feedback setting also applies. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Plays [Haptic]s through Compose's haptics (the view's, so the system setting is respected). */
@Stable
class Haptics internal constructor(private val feedback: HapticFeedback, private val enabled: Boolean) {
    fun perform(h: Haptic) {
        if (!enabled) return
        feedback.performHapticFeedback(typeOf(h))
    }

    /** A switch turned [on] or off. */
    fun toggle(on: Boolean) = perform(if (on) Haptic.TOGGLE_ON else Haptic.TOGGLE_OFF)

    /** Runs [action] after the feel of [h]. */
    fun wrap(h: Haptic, action: () -> Unit): () -> Unit = { perform(h); action() }

    companion object {
        fun typeOf(h: Haptic): HapticFeedbackType = when (h) {
            Haptic.SELECT -> HapticFeedbackType.SegmentTick
            Haptic.TAP -> HapticFeedbackType.ContextClick
            Haptic.CONFIRM -> HapticFeedbackType.Confirm
            Haptic.REJECT -> HapticFeedbackType.Reject
            Haptic.TOGGLE_ON -> HapticFeedbackType.ToggleOn
            Haptic.TOGGLE_OFF -> HapticFeedbackType.ToggleOff
            Haptic.LONG_PRESS -> HapticFeedbackType.LongPress
            Haptic.THRESHOLD -> HapticFeedbackType.GestureThresholdActivate
            Haptic.STEP -> HapticFeedbackType.SegmentFrequentTick
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}
