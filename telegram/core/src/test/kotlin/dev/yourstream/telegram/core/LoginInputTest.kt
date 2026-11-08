package dev.yourstream.telegram.core

import dev.yourstream.telegram.core.auth.LoginInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginInputTest {

    @Test
    fun phoneNumbersAreNormalisedToPlusDigits() {
        assertEquals("+919876543210", LoginInput.normalizePhone("+91 98765 43210"))
        assertEquals("+919876543210", LoginInput.normalizePhone("0091-98765-43210"))
        assertEquals("+4917012345678", LoginInput.normalizePhone(" (49) 170.1234/5678 "))
        assertEquals("+4917012345678", LoginInput.normalizePhone("4917012345678"))
    }

    @Test
    fun badPhoneNumbersAreRejected() {
        assertNull(LoginInput.normalizePhone(""))
        assertNull(LoginInput.normalizePhone("+"))
        assertNull(LoginInput.normalizePhone("+12 abc 345"))
        assertNull(LoginInput.normalizePhone("01701234567"), "national number without country code")
        assertNull(LoginInput.normalizePhone("+12345"), "too short")
        assertNull(LoginInput.normalizePhone("+1234567890123456"), "too long")
    }

    @Test
    fun digitCodesLoseSeparatorsWordCodesStayAsTyped() {
        assertEquals("12345", LoginInput.normalizeCode(" 12 345 "))
        assertEquals("12345", LoginInput.normalizeCode("123-45"))
        assertEquals("apple banana", LoginInput.normalizeCode(" apple banana "))
        assertNull(LoginInput.normalizeCode("   "))
    }

    @Test
    fun emailPlausibility() {
        assertTrue(LoginInput.isPlausibleEmail("me@example.org"))
        assertFalse(LoginInput.isPlausibleEmail("me@example"))
        assertFalse(LoginInput.isPlausibleEmail("not an email"))
    }
}
