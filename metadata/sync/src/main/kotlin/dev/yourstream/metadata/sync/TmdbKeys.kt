package dev.yourstream.metadata.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.core.network.HttpClients
import dev.yourstream.metadata.tmdb.TmdbConfig
import dev.yourstream.metadata.tmdb.TmdbProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where the TMDB key in use comes from. */
enum class TmdbKeySource { USER, BUILT_IN, NONE }

/** Encrypts small secrets for storage; the app's is [KeystoreBox]. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore under [alias] and never leaves it
 * (hardware-backed where the device has it). Stored data is the 12-byte IV, then the ciphertext.
 */
class KeystoreBox(private val alias: String) : SecretBox {
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, sealed.copyOfRange(0, 12))) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }
}

/**
 * The TMDB key: the one the user entered in Settings (stored on the device only, encrypted with a
 * Keystore key: [SecretBox]), else the one built into the release, else none (Cinemeta alone).
 * Read on every TMDB request, so a change applies at once.
 *
 * Keys saved by versions before 0.11 were plain text; the first read encrypts them and removes
 * the plain copy. A key that can no longer be decrypted (the Keystore key was lost) counts as no
 * key: the user enters it again.
 */
@Singleton
class TmdbKeys internal constructor(context: Context, private val builtIn: TmdbConfig, private val box: SecretBox) {
    @Inject constructor(@ApplicationContext context: Context, builtIn: TmdbConfig) : this(context, builtIn, KeystoreBox(KEYSTORE_ALIAS))

    private val prefs = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE)
    private val _userKey = MutableStateFlow(load())
    val userKey: StateFlow<String?> = _userKey.asStateFlow()

    private fun load(): String? {
        prefs.getString(PLAIN_KEY, null)?.let { plain ->
            val key = plain.takeIf { it.isNotBlank() }
            // Encrypt first, then drop the plain copy: a failure leaves the old copy in place.
            if (runCatching { save(key) }.isSuccess) prefs.edit().remove(PLAIN_KEY).apply()
            return key
        }
        val sealed = prefs.getString(SEALED_KEY, null) ?: return null
        return runCatching { String(box.open(android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)), Charsets.UTF_8) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun save(key: String?) {
        val edit = prefs.edit()
        if (key == null) edit.remove(SEALED_KEY)
        else edit.putString(SEALED_KEY, android.util.Base64.encodeToString(box.seal(key.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP))
        edit.remove(PLAIN_KEY).apply()
    }

    val source: TmdbKeySource
        get() = when {
            _userKey.value != null -> TmdbKeySource.USER
            builtIn.isConfigured -> TmdbKeySource.BUILT_IN
            else -> TmdbKeySource.NONE
        }

    fun current(): TmdbConfig = _userKey.value?.let(builtIn::withKey) ?: builtIn

    internal fun store(key: String?) {
        save(key)
        _userKey.value = key
    }

    internal companion object {
        /** Before 0.11: the key in plain text. */
        const val PLAIN_KEY = "user_key"
        const val SEALED_KEY = "user_key_sealed"
        const val KEYSTORE_ALIAS = "yourstream_tmdb_key"
    }
}

/**
 * Saves a TMDB key after TMDB accepts it, then re-matches titles that found nothing (or only
 * unsure matches) so their posters arrive.
 */
class SaveTmdbKey @Inject constructor(
    private val keys: TmdbKeys,
    private val db: YourStreamDatabase,
    private val clock: Clock,
    private val trigger: MetadataSyncTrigger,
) {
    suspend operator fun invoke(key: String?): AppResult<Unit> {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        if (k != null) {
            if (!TmdbConfig.looksValid(k)) return AppResult.Err(AppError.InvalidInput("TMDB_KEY", "That doesn't look like a TMDB API key (32 letters and digits) or read access token."))
            val check = TmdbProvider(HttpClients.create(), keys.current().withKey(k)).check(keys.current().withKey(k))
            if (check is AppResult.Err) {
                return AppResult.Err(
                    if (check.error is AppError.NotConfigured) AppError.InvalidInput("TMDB_KEY", "TMDB did not accept this key.") else check.error,
                )
            }
        }
        keys.store(k)
        db.metadataDao().retryUnmatched(clock.nowMillis())
        trigger.requestRun()
        return AppResult.Ok(Unit)
    }
}
