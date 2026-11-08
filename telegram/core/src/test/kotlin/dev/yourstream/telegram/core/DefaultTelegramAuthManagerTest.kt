package dev.yourstream.telegram.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.AuthState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultTelegramAuthManagerTest {

    private suspend fun TestScope.started(configure: FakeTelegramClient.() -> Unit = {}): TelegramTestEnv {
        val env = TelegramTestEnv(this)
        env.client.configure()
        env.manager.start()
        advanceUntilIdle()
        return env
    }

    private fun AppResult<*>.error(): AppError = (this as AppResult.Err).error

    @Test
    fun phoneThenCodeLogsIn() = runTest {
        val env = started()

        assertIs<AppResult.Ok<Unit>>(env.auth.submitPhoneNumber("+91 98765 43210"))
        advanceUntilIdle()
        val waitCode = assertIs<AuthState.WaitCode>(env.auth.state.value)
        assertEquals(5, waitCode.info.codeLength)
        assertTrue("setPhoneNumber:+919876543210" in env.client.calls)

        assertIs<AppResult.Ok<Unit>>(env.auth.submitCode("12 345"))
        advanceUntilIdle()
        assertEquals(AuthState.Ready, env.auth.state.value)
        assertEquals(42L, env.users.currentUser.value?.id)
    }

    @Test
    fun twoStepVerificationAsksForPassword() = runTest {
        val env = started { passwordRequired = true }
        env.auth.submitPhoneNumber("+4917012345678")
        advanceUntilIdle()
        env.auth.submitCode("12345")
        advanceUntilIdle()
        assertIs<AuthState.WaitPassword>(env.auth.state.value)

        val wrong = env.auth.submitPassword("nope")
        assertEquals("PASSWORD_HASH_INVALID", (wrong.error() as AppError.InvalidInput).reason)
        assertIs<AuthState.WaitPassword>(env.auth.state.value)

        env.auth.submitPassword("hunter2")
        advanceUntilIdle()
        assertEquals(AuthState.Ready, env.auth.state.value)
    }

    @Test
    fun wrongCodeKeepsWaitingForCode() = runTest {
        val env = started()
        env.auth.submitPhoneNumber("+4917012345678")
        advanceUntilIdle()

        val result = env.auth.submitCode("00000")
        assertEquals("PHONE_CODE_INVALID", (result.error() as AppError.InvalidInput).reason)
        assertIs<AuthState.WaitCode>(env.auth.state.value)
    }

    @Test
    fun invalidPhoneIsRejectedLocally() = runTest {
        val env = started()
        val result = env.auth.submitPhoneNumber("0170 1234567")
        assertEquals("PHONE_FORMAT", (result.error() as AppError.InvalidInput).reason)
        assertTrue(env.client.calls.none { it.startsWith("setPhoneNumber") })
    }

    @Test
    fun inputForTheWrongStepIsRejected() = runTest {
        val env = started()
        assertIs<AppError.InvalidState>(env.auth.submitCode("12345").error())
        assertIs<AppError.InvalidState>(env.auth.submitPassword("x").error())
        assertIs<AppError.InvalidState>(env.auth.resendCode().error())
        assertTrue(env.client.calls.none { it == "checkCode" || it == "checkPassword" })
    }

    @Test
    fun emptyInputsAreRejected() = runTest {
        val env = started()
        env.auth.submitPhoneNumber("+4917012345678")
        advanceUntilIdle()
        assertIs<AppError.InvalidInput>(env.auth.submitCode("  ").error())
    }

    @Test
    fun resendCodeIsForwardedWhileWaitingForCode() = runTest {
        val env = started()
        env.auth.submitPhoneNumber("+4917012345678")
        advanceUntilIdle()
        assertIs<AppResult.Ok<Unit>>(env.auth.resendCode())
        assertTrue("resendCode" in env.client.calls)
    }

    @Test
    fun qrLoginExposesTheLink() = runTest {
        val env = started()
        env.auth.requestQrCodeLogin()
        advanceUntilIdle()
        assertEquals("tg://login?token=abc", assertIs<AuthState.WaitOtherDeviceConfirmation>(env.auth.state.value).link)
    }

    @Test
    fun notConfiguredRejectsEverything() = runTest {
        val env = TelegramTestEnv(this, VALID_CONFIG.copy(apiHash = ""))
        assertIs<AuthState.NotConfigured>(env.auth.state.value)
        assertIs<AppError.NotConfigured>(env.auth.submitPhoneNumber("+4917012345678").error())
        assertIs<AppError.InvalidState>(env.auth.logOut().error())
    }

    @Test
    fun aLateAuthManagerCatchesUpWithTheCurrentState() = runTest {
        val env = started()
        val late = dev.yourstream.telegram.core.auth.DefaultTelegramAuthManager(
            env.client, VALID_CONFIG, env.scope, dev.yourstream.core.common.NoOpLogger,
        )
        advanceUntilIdle()
        assertEquals(AuthState.WaitPhoneNumber, late.state.value)
    }

    @Test
    fun aLateUserRepositoryLoadsTheUser() = runTest {
        val env = started { hasSavedSession = true }
        val late = dev.yourstream.telegram.core.user.DefaultTelegramUserRepository(
            env.client, env.scope, dev.yourstream.core.common.NoOpLogger,
        )
        advanceUntilIdle()
        assertEquals(42L, late.currentUser.value?.id)
    }
}
