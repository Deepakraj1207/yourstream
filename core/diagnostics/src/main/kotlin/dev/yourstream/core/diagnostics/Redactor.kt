package dev.yourstream.core.diagnostics

/**
 * Removes secrets from anything written to logs, crash reports or exported diagnostics:
 * phone numbers, API keys/tokens in URLs, headers and JSON, login codes, long hex secrets.
 * Applied on write, so a secret never reaches disk even if a caller forgets the logging rules.
 */
object Redactor {
    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // Authorization headers.
        Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}") to { m -> "${m.groupValues[1]} ***" },
        // key=value in URLs / text.
        Regex("(?i)\\b(api_?key|access_?token|token|api_?hash|hash|password|passwd|secret|auth|session|code)=([^&\\s\"']+)") to { m -> "${m.groupValues[1]}=***" },
        // JSON / headers.
        Regex("(?i)\"(api_?key|api_?hash|access_?token|token|password|phone_?number|phone|code|secret)\"\\s*:\\s*\"[^\"]*\"") to { m -> "\"${m.groupValues[1]}\":\"***\"" },
        Regex("(?i)\\b(x-api-key|authorization)\\s*:\\s*\\S+") to { m -> "${m.groupValues[1]}: ***" },
        // International phone numbers: keep country prefix and last two digits.
        Regex("\\+\\d[\\d -]{6,16}\\d") to { m -> m.value.filter { it.isDigit() || it == '+' }.let { d -> d.take(3) + "*".repeat((d.length - 5).coerceAtLeast(1)) + d.takeLast(2) } },
        // Login codes as people write them.
        Regex("(?i)\\b(login|auth|verification|sms)\\s+code\\s*[:=]?\\s*\\d{4,8}\\b") to { m -> "${m.groupValues[1]} code ***" },
        // Long hex strings (API hashes, tokens).
        Regex("\\b[a-fA-F0-9]{32,}\\b") to { "<hex>" },
    )

    fun redact(text: String): String = rules.fold(text) { acc, (regex, replace) -> regex.replace(acc, replace) }
}
