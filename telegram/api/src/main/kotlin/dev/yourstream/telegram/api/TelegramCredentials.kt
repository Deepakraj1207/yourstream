package dev.yourstream.telegram.api

/** A Telegram app's API id and hash (from my.telegram.org → API development tools). */
public data class ApiCredentials(val apiId: Int, val apiHash: String) {
    override fun toString(): String = "ApiCredentials(apiId=$apiId, apiHash=<hidden>)"

    public companion object {
        private val HASH = Regex("[0-9a-fA-F]{32}")

        /** Parses what a user typed; null fields say what is wrong (pure, tested). */
        public fun parse(id: String, hash: String): Parsed {
            val apiId = id.trim().toIntOrNull()?.takeIf { it > 0 }
            val apiHash = hash.trim().lowercase().takeIf { HASH.matches(it) }
            return Parsed(apiId, apiHash, if (apiId != null && apiHash != null) ApiCredentials(apiId, apiHash) else null)
        }
    }

    public data class Parsed(val apiId: Int?, val apiHash: String?, val credentials: ApiCredentials?) {
        val idValid: Boolean get() = apiId != null
        val hashValid: Boolean get() = apiHash != null
    }
}

public enum class CredentialsSource {
    /** Entered by the user in the app. */
    APP,
    /** Built into this APK (secrets.properties / CI secrets). */
    BUILD,
    NONE,
}

/**
 * The Telegram API credentials the app uses, which the user can set in the app itself (like other
 * Telegram clients that ask for your own api_id/api_hash). Stored only on this device; the hash
 * is encrypted at rest. Telegram reads them when it starts, so a change applies after a restart.
 */
public interface TelegramCredentialStore {
    /** Where the credentials the running Telegram client uses come from. */
    public val activeSource: CredentialsSource

    /** The api_id in use (ids are not secret; the hash is never exposed). */
    public val activeApiId: Int?

    /** Whether the APK itself carries credentials (so removing the app's own falls back to them). */
    public val hasBuiltIn: Boolean

    /** Stores [credentials]. Applies at the next start ([restartApp]). */
    public fun save(credentials: ApiCredentials)

    /** Forgets the credentials entered in the app (the built-in ones, if any, apply again). */
    public fun clear()

    /** Restarts the app so Telegram starts with the stored credentials. */
    public fun restartApp()
}
