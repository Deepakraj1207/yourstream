package dev.yourstream.telegram.core

import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.telegram.api.model.TelegramConfig
import dev.yourstream.telegram.core.auth.DefaultTelegramAuthManager
import dev.yourstream.telegram.core.chat.DefaultTelegramChatRepository
import dev.yourstream.telegram.core.manager.DefaultTelegramManager
import dev.yourstream.telegram.core.user.DefaultTelegramUserRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Wires the real Telegram layer to [FakeTelegramClient], as Hilt does with TDLib.
 *
 * The layer's scope runs on the test scheduler (so `advanceUntilIdle()` drives it) but is not a
 * child of the test, because its update collectors never finish. `backgroundScope` would not
 * do: `advanceUntilIdle()` deliberately skips background work.
 */
class TelegramTestEnv(testScope: TestScope, config: TelegramConfig = VALID_CONFIG) {
    val uncaught = mutableListOf<Throwable>()
    val scope = CoroutineScope(
        SupervisorJob() + StandardTestDispatcher(testScope.testScheduler) +
            CoroutineExceptionHandler { _, t -> uncaught += t },
    )

    val client = FakeTelegramClient()
    val auth = DefaultTelegramAuthManager(client, config, scope, NoOpLogger)
    val chats = DefaultTelegramChatRepository(client, scope, NoOpLogger)
    val users = DefaultTelegramUserRepository(client, scope, NoOpLogger)
    val manager = DefaultTelegramManager(client, config, scope, NoOpLogger)
}
