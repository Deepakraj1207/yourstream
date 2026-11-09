package dev.yourstream.telegram.tdlib.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.yourstream.core.common.Logger
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.TelegramManager
import dev.yourstream.telegram.api.TelegramMessageRepository
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.core.TelegramScope
import dev.yourstream.telegram.core.auth.DefaultTelegramAuthManager
import dev.yourstream.telegram.core.chat.DefaultTelegramChatRepository
import dev.yourstream.telegram.core.client.TelegramClient
import dev.yourstream.telegram.core.file.DefaultTelegramFileRepository
import dev.yourstream.telegram.core.manager.DefaultTelegramManager
import dev.yourstream.telegram.core.message.DefaultTelegramMessageRepository
import dev.yourstream.telegram.core.user.DefaultTelegramUserRepository
import dev.yourstream.telegram.tdlib.TdlibTelegramClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Binds the Telegram layer. The app module must provide a
 * [dev.yourstream.telegram.api.model.TelegramConfig] and a [Logger].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TelegramModule {

    @Binds abstract fun client(impl: TdlibTelegramClient): TelegramClient
    @Binds abstract fun manager(impl: DefaultTelegramManager): TelegramManager
    @Binds abstract fun auth(impl: DefaultTelegramAuthManager): TelegramAuthManager
    @Binds abstract fun chats(impl: DefaultTelegramChatRepository): TelegramChatRepository
    @Binds abstract fun users(impl: DefaultTelegramUserRepository): TelegramUserRepository
    @Binds abstract fun files(impl: DefaultTelegramFileRepository): TelegramFileRepository
    @Binds abstract fun messages(impl: DefaultTelegramMessageRepository): TelegramMessageRepository
    @Binds abstract fun savedMessages(impl: dev.yourstream.telegram.core.saved.DefaultTelegramSavedMessages): dev.yourstream.telegram.api.TelegramSavedMessages

    companion object {
        @Provides
        @Singleton
        @TelegramScope
        fun telegramScope(logger: Logger): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, t -> logger.e("Telegram", "uncaught error in Telegram layer", t) },
        )
    }
}
