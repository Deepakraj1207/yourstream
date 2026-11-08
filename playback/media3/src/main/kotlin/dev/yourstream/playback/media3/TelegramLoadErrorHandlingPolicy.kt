package dev.yourstream.playback.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import dev.yourstream.playback.stream.TelegramStreamException

/**
 * Retries Telegram load errors that can recover (stalls, network, server errors) with
 * exponential backoff; gives up at once on files that are gone. After [maxRetries] failed
 * attempts the error reaches the player (which can still be retried by the user or
 * automatically when the network returns).
 */
@OptIn(UnstableApi::class)
class TelegramLoadErrorHandlingPolicy(
    private val maxRetries: Int = 5,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 8_000,
) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val telegram = loadErrorInfo.exception.telegramCause() ?: return super.getRetryDelayMsFor(loadErrorInfo)
        if (!telegram.retryable) return C.TIME_UNSET
        val attempt = (loadErrorInfo.errorCount - 1).coerceIn(0, 10)
        return minOf(maxDelayMs, baseDelayMs shl attempt)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = maxRetries
}

internal fun Throwable.telegramCause(): TelegramStreamException? =
    generateSequence(this) { it.cause }.take(10).filterIsInstance<TelegramStreamException>().firstOrNull()
