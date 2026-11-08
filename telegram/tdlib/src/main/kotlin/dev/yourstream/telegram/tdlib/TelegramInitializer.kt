package dev.yourstream.telegram.tdlib

import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.TelegramUserRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Starts the Telegram layer once per process (call from Application.onCreate).
 *
 * The auth manager and repositories mirror TDLib updates, which have no replay, so they are
 * created *before* TDLib starts. Creating them later still works (they ask for the current
 * state), but chats announced before then are only picked up by the next page load.
 */
@Singleton
class TelegramInitializer @Inject constructor(
    private val manager: TelegramManager,
    private val auth: Provider<TelegramAuthManager>,
    private val chats: Provider<TelegramChatRepository>,
    private val users: Provider<TelegramUserRepository>,
    private val files: Provider<TelegramFileRepository>,
    private val networkMonitor: AndroidNetworkMonitor,
    private val logger: Logger,
) {
    suspend fun start(): AppResult<Unit> {
        auth.get(); chats.get(); users.get(); files.get()
        val result = manager.start()
        if (result is AppResult.Err) logger.e(TAG, "Telegram did not start: ${result.error.message}")
        networkMonitor.start()
        return result
    }

    private companion object {
        const val TAG = "TgInit"
    }
}
