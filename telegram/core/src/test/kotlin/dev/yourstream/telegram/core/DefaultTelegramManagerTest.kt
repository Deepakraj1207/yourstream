package dev.yourstream.telegram.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.api.model.TelegramStatus
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultTelegramManagerTest {

    @Test
    fun startSendsParametersAndWaitsForPhoneNumber() = runTest {
        val env = TelegramTestEnv(this)
        assertIs<AppResult.Ok<Unit>>(env.manager.start())
        advanceUntilIdle()

        assertEquals(listOf("open", "setParameters"), env.client.calls)
        assertEquals(AuthState.WaitPhoneNumber, env.auth.state.value)
        assertEquals(TelegramStatus.Running, env.manager.status.value)
    }

    @Test
    fun savedSessionResumesWithoutLogin() = runTest {
        val env = TelegramTestEnv(this)
        env.client.hasSavedSession = true
        env.manager.start()
        advanceUntilIdle()

        assertEquals(AuthState.Ready, env.auth.state.value)
        assertEquals(42L, env.users.currentUser.value?.id)
        assertTrue(env.client.calls.none { it.startsWith("setPhoneNumber") })
    }

    @Test
    fun startIsIdempotent() = runTest {
        val env = TelegramTestEnv(this)
        env.manager.start()
        env.manager.start()
        advanceUntilIdle()
        assertEquals(1, env.client.openCount)
    }

    @Test
    fun missingCredentialsNeverOpenTdlib() = runTest {
        val env = TelegramTestEnv(this, VALID_CONFIG.copy(apiId = 0))
        val result = env.manager.start()

        assertIs<AppError.NotConfigured>((result as AppResult.Err).error)
        assertIs<TelegramStatus.NotConfigured>(env.manager.status.value)
        assertIs<AuthState.NotConfigured>(env.auth.state.value)
        assertTrue(env.client.calls.isEmpty())
    }

    @Test
    fun logoutRecreatesTheInstanceAndReturnsToPhoneNumber() = runTest {
        val env = TelegramTestEnv(this)
        env.client.hasSavedSession = true
        env.manager.start()
        advanceUntilIdle()

        env.auth.logOut()
        advanceUntilIdle()

        assertEquals(2, env.client.openCount)
        assertEquals(AuthState.WaitPhoneNumber, env.auth.state.value)
        assertEquals(TelegramStatus.Running, env.manager.status.value)
        assertEquals(null, env.users.currentUser.value)
    }

    @Test
    fun restartKeepsTheSession() = runTest {
        val env = TelegramTestEnv(this)
        env.client.hasSavedSession = true
        env.manager.start()
        advanceUntilIdle()

        env.manager.restart()
        advanceUntilIdle()

        assertEquals(2, env.client.openCount)
        assertEquals(AuthState.Ready, env.auth.state.value)
    }

    @Test
    fun stopLeavesTdlibClosed() = runTest {
        val env = TelegramTestEnv(this)
        env.manager.start()
        advanceUntilIdle()

        env.manager.stop()
        advanceUntilIdle()

        assertEquals(1, env.client.openCount)
        assertEquals(TelegramStatus.Stopped, env.manager.status.value)
        assertEquals(AuthState.Closed, env.auth.state.value)
    }

    @Test
    fun rejectedParametersFailTheManager() = runTest {
        val env = TelegramTestEnv(this)
        env.client.parametersError = AppError.Telegram(400, "Wrong database encryption key")
        env.manager.start()
        advanceUntilIdle()

        assertIs<TelegramStatus.Failed>(env.manager.status.value)
    }

    @Test
    fun connectionStateIsTrackedAndResetOnClose() = runTest {
        val env = TelegramTestEnv(this)
        env.manager.start()
        advanceUntilIdle()

        env.client.emit(TelegramEvent.ConnectionStateChanged(TelegramConnectionState.READY))
        advanceUntilIdle()
        assertEquals(TelegramConnectionState.READY, env.manager.connectionState.value)

        env.manager.stop()
        advanceUntilIdle()
        assertEquals(TelegramConnectionState.UNKNOWN, env.manager.connectionState.value)
    }

    @Test
    fun networkChangesAndReconnectReachTdlib() = runTest {
        val env = TelegramTestEnv(this)
        env.manager.start()
        advanceUntilIdle()

        env.manager.onNetworkChanged(NetworkType.WIFI)
        env.manager.onNetworkChanged(NetworkType.WIFI) // duplicate is ignored
        env.manager.reconnect()

        assertEquals(listOf("setNetworkType:WIFI", "setNetworkType:WIFI"), env.client.calls.filter { it.startsWith("setNetworkType") })
    }

    @Test
    fun reconnectBeforeStartIsRejected() = runTest {
        val env = TelegramTestEnv(this)
        assertIs<AppError.InvalidState>((env.manager.reconnect() as AppResult.Err).error)
    }
}
