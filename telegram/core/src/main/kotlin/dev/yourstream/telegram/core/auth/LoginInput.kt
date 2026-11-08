package dev.yourstream.telegram.core.auth

/** Input normalisation for the login flow. Pure functions, unit-tested. */
object LoginInput {

    private val PHONE_SEPARATORS = setOf(' ', '-', '(', ')', '.', '/', ' ')
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /**
     * International number as "+digits", or null if it cannot be one. A leading "00" is the
     * international prefix. Country codes never start with 0, so "0170..." (a national
     * number) is rejected rather than sent to Telegram as garbage.
     */
    fun normalizePhone(input: String): String? {
        var s = input.trim().filterNot { it in PHONE_SEPARATORS }
        if (s.startsWith("00")) s = "+" + s.drop(2)
        val digits = s.removePrefix("+")
        if (digits.isEmpty() || !digits.all(Char::isDigit)) return null
        if (digits.startsWith('0')) return null
        if (digits.length !in 7..15) return null
        return "+$digits"
    }

    /**
     * Login codes are usually digits ("12 345" pasted with spaces is fine) but SMS_WORD and
     * SMS_PHRASE codes are words, so non-digit codes are passed through trimmed.
     */
    fun normalizeCode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val compact = trimmed.filterNot { it == ' ' || it == '-' }
        return if (compact.isNotEmpty() && compact.all(Char::isDigit)) compact else trimmed
    }

    fun isPlausibleEmail(input: String): Boolean = EMAIL.matches(input.trim())
}
