package dev.yourstream.ui.screens

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.ui.screens.settings.AccountDataStore
import dev.yourstream.ui.screens.settings.AccountSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sign-out rule: while signed out, no account data stays on the device. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionTest {
    private class FakeData(var rows: Int = 0) : AccountDataStore {
        var wipes = 0
        var failNext = false
        override suspend fun hasAccountData() = rows > 0
        override suspend fun wipe() {
            if (failNext) { failNext = false; throw IllegalStateException("disk full") }
            wipes++; rows = 0
        }
        override var signedOutNotice = false
    }

    private class FakeAuth(initial: AuthState) : TelegramAuthManager {
        override val state = MutableStateFlow(initial)
        var logOutResult: AppResult<Unit> = AppResult.Ok(Unit)
        override suspend fun logOut(): AppResult<Unit> {
            if (logOutResult is AppResult.Ok) state.value = AuthState.LoggingOut
            return logOutResult
        }
        override suspend fun submitPhoneNumber(phoneNumber: String) = AppResult.Ok(Unit)
        override suspend fun submitCode(code: String) = AppResult.Ok(Unit)
        override suspend fun resendCode() = AppResult.Ok(Unit)
        override suspend fun submitPassword(password: String) = AppResult.Ok(Unit)
        override suspend fun submitEmailAddress(email: String) = AppResult.Ok(Unit)
        override suspend fun submitEmailCode(code: String) = AppResult.Ok(Unit)
        override suspend fun requestQrCodeLogin() = AppResult.Ok(Unit)
    }

    private fun TestScope.session(auth: FakeAuth, data: FakeData) =
        AccountSession(auth, data, NoOpLogger).also { it.start(TestScope(UnconfinedTestDispatcher(testScheduler))) }

    @Test
    fun signingOutWipesWithoutANotice() = runTest(UnconfinedTestDispatcher()) {
        val auth = FakeAuth(AuthState.Ready)
        val data = FakeData(rows = 500)
        val s = session(auth, data)
        assertTrue(s.signOut() is AppResult.Ok)
        assertEquals(1, data.wipes)
        assertFalse(data.rows > 0)
        auth.state.value = AuthState.Closed
        auth.state.value = AuthState.WaitPhoneNumber
        assertEquals(1, data.wipes, "nothing left to wipe the second time")
        assertFalse(s.notice.value, "the user asked: no 'you were signed out'")
    }

    @Test
    fun aSessionEndedElsewhereWipesAndExplains() = runTest(UnconfinedTestDispatcher()) {
        val auth = FakeAuth(AuthState.Ready)
        val data = FakeData(rows = 500)
        val s = session(auth, data)
        // Terminated from another device: TDLib logs out on its own.
        auth.state.value = AuthState.LoggingOut
        assertEquals(1, data.wipes)
        assertTrue(s.notice.value)
        assertTrue(data.signedOutNotice, "kept across restarts")
        // Signing in again clears it.
        auth.state.value = AuthState.WaitPhoneNumber
        auth.state.value = AuthState.Ready
        assertFalse(s.notice.value)
    }

    @Test
    fun aSkippedLoggingOutStateIsStillASignOut() = runTest(UnconfinedTestDispatcher()) {
        val auth = FakeAuth(AuthState.Ready)
        val data = FakeData(rows = 3)
        val s = session(auth, data)
        auth.state.value = AuthState.WaitPhoneNumber
        assertEquals(1, data.wipes)
        assertTrue(s.notice.value)
    }

    @Test
    fun leftoversFromAnEarlierVersionAreRemovedWhileSignedOut() = runTest(UnconfinedTestDispatcher()) {
        // Before 0.11, signing out kept the library: started signed out with data still there.
        val auth = FakeAuth(AuthState.WaitPhoneNumber)
        val data = FakeData(rows = 42)
        val s = session(auth, data)
        assertEquals(1, data.wipes)
        assertFalse(s.notice.value, "not signed in during this run: nothing to explain")
    }

    @Test
    fun aRestartKeepsEverything() = runTest(UnconfinedTestDispatcher()) {
        val auth = FakeAuth(AuthState.Ready)
        val data = FakeData(rows = 42)
        session(auth, data)
        for (st in listOf(AuthState.Closing, AuthState.Closed, AuthState.WaitParameters, AuthState.Ready)) auth.state.value = st
        assertEquals(0, data.wipes)
        assertEquals(42, data.rows)
    }

    @Test
    fun aFailedWipeIsRetriedAtTheNextSignedOutState() = runTest(UnconfinedTestDispatcher()) {
        val auth = FakeAuth(AuthState.Ready)
        val data = FakeData(rows = 7).apply { failNext = true }
        val s = session(auth, data)
        auth.state.value = AuthState.LoggingOut
        assertEquals(0, data.wipes)
        assertFalse(s.wiping.value)
        auth.state.value = AuthState.WaitPhoneNumber
        assertEquals(1, data.wipes)
        assertEquals(0, data.rows)
    }

    @Test
    fun aRefusedSignOutChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val auth = FakeAuth(AuthState.Ready).apply { logOutResult = AppResult.Err(AppError.Network()) }
        val data = FakeData(rows = 9)
        val s = session(auth, data)
        assertTrue(s.signOut() is AppResult.Err)
        assertEquals(0, data.wipes)
        // A later sign-out from elsewhere is still explained.
        auth.state.value = AuthState.LoggingOut
        assertTrue(s.notice.value)
    }
}
