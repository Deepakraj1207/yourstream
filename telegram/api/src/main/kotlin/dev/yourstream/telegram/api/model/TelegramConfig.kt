package dev.yourstream.telegram.api.model

/**
 * Everything TDLib needs to start. Built by the app from BuildConfig (see docs/TELEGRAM_SETUP.md);
 * the API id/hash are never stored in source control.
 */
public data class TelegramConfig(
    val apiId: Int,
    val apiHash: String,
    /** Directory for TDLib's database, which holds the session. Must survive app restarts. */
    val databaseDirectory: String,
    /** Directory for downloaded files. May be a cache directory. */
    val filesDirectory: String,
    val useTestDc: Boolean = false,
    val systemLanguageCode: String = "en",
    val deviceModel: String = "Android",
    val systemVersion: String = "",
    val applicationVersion: String = "0.1",
    /** TDLib's own log verbosity: 0 fatal, 1 errors, 2 warnings, 3 info, 4 debug, 5 verbose. */
    val tdlibLogVerbosity: Int = 1,
    /** If set, TDLib writes its internal log here (rotated at [tdlibLogMaxBytes]). */
    val tdlibLogFile: String? = null,
    val tdlibLogMaxBytes: Long = 10L * 1024 * 1024,
    /** Optional TDLib database encryption key. Empty means unencrypted (app sandbox only). */
    val databaseEncryptionKey: ByteArray = ByteArray(0),
) {
    /** Null when usable, otherwise a human-readable reason (never echoes the hash itself). */
    public fun validationError(): String? = when {
        apiId <= 0 -> "Telegram API id is missing. Set telegram.apiId (see docs/TELEGRAM_SETUP.md)."
        apiHash.isBlank() -> "Telegram API hash is missing. Set telegram.apiHash (see docs/TELEGRAM_SETUP.md)."
        !apiHash.matches(HASH_PATTERN) -> "Telegram API hash must be 32 hexadecimal characters."
        databaseDirectory.isBlank() -> "TDLib database directory is not set."
        else -> null
    }

    // ByteArray breaks data-class equality; compare by content and never print secrets.
    override fun equals(other: Any?): Boolean = other is TelegramConfig &&
        apiId == other.apiId && apiHash == other.apiHash &&
        databaseDirectory == other.databaseDirectory && filesDirectory == other.filesDirectory &&
        useTestDc == other.useTestDc && systemLanguageCode == other.systemLanguageCode &&
        deviceModel == other.deviceModel && systemVersion == other.systemVersion &&
        applicationVersion == other.applicationVersion && tdlibLogVerbosity == other.tdlibLogVerbosity &&
        tdlibLogFile == other.tdlibLogFile && tdlibLogMaxBytes == other.tdlibLogMaxBytes &&
        databaseEncryptionKey.contentEquals(other.databaseEncryptionKey)

    override fun hashCode(): Int = 31 * apiId + databaseDirectory.hashCode()

    override fun toString(): String =
        "TelegramConfig(apiId=${if (apiId > 0) "set" else "missing"}, apiHash=${if (apiHash.isNotBlank()) "set" else "missing"}, " +
            "useTestDc=$useTestDc, databaseDirectory=$databaseDirectory)"

    private companion object {
        val HASH_PATTERN = Regex("[0-9a-fA-F]{32}")
    }
}
