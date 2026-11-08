package dev.yourstream.playback.media3

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaybackRecoveryPolicyTest {
    private val policy = PlaybackRecoveryPolicy(maxRetries = 3, maxSwitches = 2, baseDelayMs = 1_000, maxDelayMs = 3_000)
    private val network = PlaybackError(PlaybackErrorKind.NETWORK, "timeout", retryable = true)
    private val gone = PlaybackError(PlaybackErrorKind.FILE_UNAVAILABLE, "deleted", retryable = false)

    @Test
    fun networkErrors() {
        assertEquals(RecoveryAction.Retry(1_000, 1), policy.decide(network, 0, 0, networkAvailable = true, canSwitch = true))
        assertEquals(RecoveryAction.Retry(2_000, 2), policy.decide(network, 1, 0, null, true))
        assertEquals(RecoveryAction.Retry(3_000, 3), policy.decide(network, 2, 0, null, true), "capped")
        assertIs<RecoveryAction.GiveUp>(policy.decide(network, 3, 0, true, true))
        assertEquals(RecoveryAction.WaitForNetwork, policy.decide(network, 5, 0, networkAvailable = false, canSwitch = true), "offline: wait, do not burn retries")
        assertEquals(RecoveryAction.SwitchVersion, policy.decide(network.copy(retryable = false), 0, 0, true, true))
    }

    @Test
    fun missingOrUnplayableFilesSwitchVersion() {
        assertEquals(RecoveryAction.SwitchVersion, policy.decide(gone, 0, 0, true, canSwitch = true))
        assertEquals(RecoveryAction.SwitchVersion, policy.decide(gone.copy(kind = PlaybackErrorKind.DECODER), 0, 1, true, true))
        assertIs<RecoveryAction.GiveUp>(policy.decide(gone, 0, 2, true, true), "at most two switches")
        assertIs<RecoveryAction.GiveUp>(policy.decide(gone.copy(kind = PlaybackErrorKind.UNSUPPORTED_FORMAT), 0, 0, true, canSwitch = false))
    }

    @Test
    fun unknownErrors() {
        val unknown = PlaybackError(PlaybackErrorKind.UNKNOWN, "?", retryable = false)
        assertIs<RecoveryAction.Retry>(policy.decide(unknown, 0, 0, true, true))
        assertEquals(RecoveryAction.SwitchVersion, policy.decide(unknown, 1, 0, true, true))
        assertIs<RecoveryAction.GiveUp>(policy.decide(unknown, 1, 0, true, false))
    }
}
