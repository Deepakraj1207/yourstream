package dev.yourstream.core.common

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetryTest {
    private val noJitter = RetryPolicy(maxAttempts = 4, baseDelayMs = 100, maxDelayMs = 1_000, jitter = 0.0)

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(listOf(0L, 0L, 100L, 200L, 400L, 800L, 1_000L, 1_000L), (0..7).map { noJitter.delayBefore(it) })
        val jittered = RetryPolicy(baseDelayMs = 1_000, jitter = 0.2)
        repeat(50) { assertTrue(jittered.delayBefore(2, Random(it)) in 800..1_200) }
    }

    @Test
    fun retriesTransientErrorsUntilSuccess() = runTest {
        var calls = 0
        val r = retrying(noJitter) { calls++; if (calls < 3) AppResult.Err(AppError.Network()) else AppResult.Ok("done") }
        assertEquals(AppResult.Ok("done"), r)
        assertEquals(3, calls)
        assertEquals(300L, currentTime, "100 + 200 ms of backoff")
    }

    @Test
    fun permanentErrorsAndExhaustion() = runTest {
        var calls = 0
        assertTrue(retrying(noJitter) { calls++; AppResult.Err(AppError.NotFound("gone")) } is AppResult.Err)
        assertEquals(1, calls, "not found is not retried")
        calls = 0
        val seen = mutableListOf<Int>()
        val r = retrying(noJitter, onRetry = { a, _, _ -> seen += a }) { calls++; AppResult.Err(AppError.Remote(503, "down")) }
        assertEquals(AppError.Remote(503, "down"), (r as AppResult.Err).error)
        assertEquals(4, calls)
        assertEquals(listOf(2, 3, 4), seen)
    }

    @Test
    fun rateLimitsWaitAtLeastRetryAfter() = runTest {
        var calls = 0
        retrying(noJitter) { calls++; if (calls == 1) AppResult.Err(AppError.RateLimited(5)) else AppResult.Ok(Unit) }
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun transientClassification() {
        assertTrue(AppError.Timeout("x").isTransient())
        assertTrue(AppError.Remote(502, "").isTransient())
        assertTrue(!AppError.Remote(404, "").isTransient())
        assertTrue(!AppError.Unauthorized().isTransient())
        assertTrue(!AppError.NotConfigured("").isTransient())
    }
}
