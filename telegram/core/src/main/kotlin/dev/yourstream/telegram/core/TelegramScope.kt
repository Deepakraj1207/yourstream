package dev.yourstream.telegram.core

import javax.inject.Qualifier

/**
 * The long-lived [kotlinx.coroutines.CoroutineScope] the Telegram layer runs its update
 * collectors in. Must use a SupervisorJob so one failing collector does not stop the others.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TelegramScope
