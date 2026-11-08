package dev.yourstream.telegram.core

import dev.yourstream.core.common.AppError
import dev.yourstream.telegram.core.error.TelegramErrorMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramErrorMapperTest {

    @Test
    fun floodWaitBecomesRateLimitedWithSeconds() {
        assertEquals(AppError.RateLimited(37), TelegramErrorMapper.map(429, "Too Many Requests: retry after 37"))
        assertEquals(AppError.RateLimited(5), TelegramErrorMapper.map(420, "FLOOD_WAIT_5"))
        assertEquals(AppError.RateLimited(0), TelegramErrorMapper.map(429, "Too Many Requests"))
    }

    @Test
    fun knownLoginErrorsBecomeInvalidInputWithFriendlyText() {
        val error = TelegramErrorMapper.map(400, "PHONE_CODE_INVALID")
        assertIs<AppError.InvalidInput>(error)
        assertEquals("PHONE_CODE_INVALID", error.reason)
        assertEquals("The code is wrong.", error.message)
        assertIs<AppError.InvalidInput>(TelegramErrorMapper.map(400, "PASSWORD_HASH_INVALID"))
        assertIs<AppError.InvalidInput>(TelegramErrorMapper.map(400, "PHONE_NUMBER_INVALID"))
    }

    @Test
    fun revokedSessionIsUnauthorized() {
        assertIs<AppError.Unauthorized>(TelegramErrorMapper.map(401, "SESSION_REVOKED"))
        assertIs<AppError.Unauthorized>(TelegramErrorMapper.map(401, "Unauthorized"))
    }

    @Test
    fun badApiCredentialsAreAConfigurationProblem() {
        assertIs<AppError.NotConfigured>(TelegramErrorMapper.map(400, "API_ID_INVALID"))
    }

    @Test
    fun networkAndStateErrors() {
        assertIs<AppError.Network>(TelegramErrorMapper.map(500, "Request aborted"))
        assertIs<AppError.InvalidState>(TelegramErrorMapper.map(400, "Unexpected setAuthenticationPhoneNumber"))
        assertIs<AppError.NotFound>(TelegramErrorMapper.map(404, "Not Found"))
    }

    @Test
    fun anythingElseIsKeptVerbatim() {
        assertEquals(AppError.Telegram(400, "CHANNEL_PRIVATE"), TelegramErrorMapper.map(400, "CHANNEL_PRIVATE"))
    }
}
