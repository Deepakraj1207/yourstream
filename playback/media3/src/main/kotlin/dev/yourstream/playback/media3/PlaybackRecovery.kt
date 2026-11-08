package dev.yourstream.playback.media3

/** What to do after a playback error. */
sealed interface RecoveryAction {
    /** Re-prepare after [delayMs] (position kept). */
    data class Retry(val delayMs: Long, val attempt: Int) : RecoveryAction
    /** Offline: wait; the controller retries when the network returns. */
    data object WaitForNetwork : RecoveryAction
    /** The file is gone or unplayable here: play another version of the same title from the same position. */
    data object SwitchVersion : RecoveryAction
    /** Show the error; the user can retry by hand. */
    data class GiveUp(val reason: String) : RecoveryAction
}

/**
 * Error → recovery, pure:
 *  - network errors: retry with exponential backoff while the network is up, wait while it is
 *    down (the controller resumes on reconnect), give up after [maxRetries];
 *  - file gone / unsupported / decoder failure: switch to another version of the title (up to
 *    [maxSwitches]) — a different file, often a different encoding;
 *  - unknown errors: one retry, then a version switch, then give up.
 */
class PlaybackRecoveryPolicy(
    val maxRetries: Int = 3,
    val maxSwitches: Int = 2,
    private val baseDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 30_000,
) {
    fun decide(error: PlaybackError, retriesSoFar: Int, switchesSoFar: Int, networkAvailable: Boolean?, canSwitch: Boolean): RecoveryAction {
        val switch = canSwitch && switchesSoFar < maxSwitches
        return when (error.kind) {
            PlaybackErrorKind.NETWORK -> when {
                !error.retryable -> if (switch) RecoveryAction.SwitchVersion else RecoveryAction.GiveUp("network error that retrying cannot fix")
                networkAvailable == false -> RecoveryAction.WaitForNetwork
                retriesSoFar < maxRetries -> retry(retriesSoFar)
                else -> RecoveryAction.GiveUp("still failing after $maxRetries retries")
            }
            PlaybackErrorKind.FILE_UNAVAILABLE, PlaybackErrorKind.UNSUPPORTED_FORMAT, PlaybackErrorKind.DECODER ->
                if (switch) RecoveryAction.SwitchVersion else RecoveryAction.GiveUp(if (canSwitch) "no other version left" else "no other version of this title")
            PlaybackErrorKind.UNKNOWN -> when {
                retriesSoFar < 1 -> retry(retriesSoFar)
                switch -> RecoveryAction.SwitchVersion
                else -> RecoveryAction.GiveUp("unexpected error")
            }
        }
    }

    private fun retry(soFar: Int) = RecoveryAction.Retry((baseDelayMs shl soFar.coerceAtMost(10)).coerceAtMost(maxDelayMs), soFar + 1)
}
