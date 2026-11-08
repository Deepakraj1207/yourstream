package dev.yourstream.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuardTest {
    @Test
    fun exceptionsBecomeErrorsButCancellationPropagates() = runTest {
        assertEquals(AppResult.Ok(1), guarded("x") { AppResult.Ok(1) })
        val e = (guarded<Int>("tmdb search") { throw IllegalStateException("results was a string") } as AppResult.Err).error
        assertIs<AppError.Unknown>(e)
        assertTrue(e.message.startsWith("tmdb search failed: IllegalStateException"))
        assertFailsWith<CancellationException> { guarded<Int>("x") { throw CancellationException("stop") } }
    }
}
