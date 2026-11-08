package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.TelegramUser
import kotlinx.coroutines.flow.StateFlow

public interface TelegramUserRepository {
    /** The logged-in account; null while logged out. Kept current by Telegram updates. */
    public val currentUser: StateFlow<TelegramUser?>

    public suspend fun refreshCurrentUser(): AppResult<TelegramUser>

    public suspend fun getUser(userId: Long): AppResult<TelegramUser>
}
