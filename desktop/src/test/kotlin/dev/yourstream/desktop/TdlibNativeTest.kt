package dev.yourstream.desktop

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.TelegramConfig
import dev.yourstream.telegram.core.auth.DefaultTelegramAuthManager
import dev.yourstream.telegram.core.manager.DefaultTelegramManager
import dev.yourstream.telegram.tdlib.TdlibTelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDLib's native library loads on this OS (on Windows: the DLLs inside tdl-coroutines' JVM jar)
 * and the shared Telegram client starts it up to the phone-number step. No network or account
 * is needed for that, and nothing is sent to Telegram.
 */
class TdlibNativeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun tdlibStartsAndAsksForAPhoneNumber() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val config = TelegramConfig(
            apiId = 1, apiHash = "0".repeat(32),
            databaseDirectory = tmp.newFolder("db").absolutePath, filesDirectory = tmp.newFolder("files").absolutePath,
            deviceModel = "Test", applicationVersion = "test",
        )
        val client = TdlibTelegramClient(scope, NoOpLogger)
        val manager = DefaultTelegramManager(client, config, scope, NoOpLogger)
        val auth = DefaultTelegramAuthManager(client, config, scope, NoOpLogger)
        try {
            val started = manager.start()
            assertTrue(started is dev.yourstream.core.common.AppResult.Ok, "TDLib started: $started")
            val state = withTimeout(30_000) { auth.state.first { it == AuthState.WaitPhoneNumber || it is AuthState.NotConfigured } }
            assertEquals(AuthState.WaitPhoneNumber, state)
        } finally {
            manager.stop()
            scope.cancel()
        }
    }
}
