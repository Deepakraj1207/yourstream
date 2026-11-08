package dev.yourstream.core.common

/**
 * Minimal logging port. Implementations decide where lines go (Logcat, an in-app debug buffer).
 *
 * Rule for callers: never pass phone numbers, login codes, passwords, API hashes or session
 * data. Use [redactPhone] when a number must be referenced.
 */
public interface Logger {
    public fun d(tag: String, message: String)
    public fun i(tag: String, message: String)
    public fun w(tag: String, message: String, throwable: Throwable? = null)
    public fun e(tag: String, message: String, throwable: Throwable? = null)
}

public object NoOpLogger : Logger {
    override fun d(tag: String, message: String): Unit = Unit
    override fun i(tag: String, message: String): Unit = Unit
    override fun w(tag: String, message: String, throwable: Throwable?): Unit = Unit
    override fun e(tag: String, message: String, throwable: Throwable?): Unit = Unit
}

/** "+491701234567" -> "+49********67". Keeps enough to recognise a number in logs, no more. */
public fun redactPhone(phone: String): String {
    if (phone.length <= 5) return "***"
    return phone.take(3) + "*".repeat(phone.length - 5) + phone.takeLast(2)
}
