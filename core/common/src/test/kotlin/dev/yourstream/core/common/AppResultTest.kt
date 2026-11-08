package dev.yourstream.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppResultTest {
    @Test
    fun mapTransformsOkAndKeepsErr() {
        assertEquals(AppResult.Ok(4), AppResult.Ok(2).map { it * 2 })
        val err: AppResult<Int> = AppError.NotFound("x").err()
        assertEquals(err, err.map { it * 2 })
    }

    @Test
    fun getOrNullAndErrorOrNull() {
        assertEquals(1, 1.ok().getOrNull())
        assertNull(AppError.Network().err().getOrNull())
        assertEquals(AppError.Network(), AppError.Network().err().errorOrNull())
    }

    @Test
    fun redactPhoneHidesTheMiddle() {
        assertEquals("+49********67", redactPhone("+491701234567"))
        assertEquals("***", redactPhone("+123"))
    }
}
