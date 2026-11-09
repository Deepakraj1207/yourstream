package dev.yourstream.di

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.BuildConfig
import dev.yourstream.telegram.api.ApiCredentials
import dev.yourstream.telegram.api.CredentialsSource
import dev.yourstream.telegram.api.TelegramCredentialStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telegram API credentials entered in the app. App-private storage; the hash is encrypted with a
 * non-exportable Android Keystore key. Credentials entered here win over the ones built into the
 * APK. Read once when Telegram is configured (process start), so a change applies after a restart.
 */
@Singleton
class AndroidTelegramCredentialStore @Inject constructor(@ApplicationContext private val context: Context) : TelegramCredentialStore {
    private val prefs = context.getSharedPreferences("telegram_credentials", Context.MODE_PRIVATE)

    /** What this process started Telegram with (fixed for the process lifetime). */
    private val started: Pair<CredentialsSource, ApiCredentials?> by lazy { resolve() }

    override val activeSource: CredentialsSource get() = started.first
    override val activeApiId: Int? get() = started.second?.apiId
    override val hasBuiltIn: Boolean get() = builtIn() != null

    /** The credentials to start Telegram with; resets the old session first if they changed. */
    fun forTelegram(): ApiCredentials? {
        if (prefs.getBoolean(KEY_RESET, false)) {
            // A Telegram session belongs to the api_id it was created with: start a fresh one.
            // Only TDLib's own data goes; the library, progress and settings stay.
            File(context.noBackupFilesDir, "tdlib/db").deleteRecursively()
            File(context.cacheDir, "tdlib/files").deleteRecursively()
            prefs.edit().putBoolean(KEY_RESET, false).commit()
        }
        return started.second
    }

    private fun resolve(): Pair<CredentialsSource, ApiCredentials?> {
        stored()?.let { return CredentialsSource.APP to it }
        builtIn()?.let { return CredentialsSource.BUILD to it }
        return CredentialsSource.NONE to null
    }

    private fun builtIn(): ApiCredentials? =
        ApiCredentials.parse(BuildConfig.TELEGRAM_API_ID.toString(), BuildConfig.TELEGRAM_API_HASH).credentials

    private fun stored(): ApiCredentials? {
        val id = prefs.getInt(KEY_ID, 0).takeIf { it > 0 } ?: return null
        val hash = prefs.getString(KEY_HASH, null)?.let { runCatching { String(unwrap(Base64.decode(it, Base64.NO_WRAP))) }.getOrNull() } ?: return null
        return ApiCredentials.parse(id.toString(), hash).credentials
    }

    override fun save(credentials: ApiCredentials) {
        val changed = credentials != started.second
        prefs.edit()
            .putInt(KEY_ID, credentials.apiId)
            .putString(KEY_HASH, Base64.encodeToString(wrap(credentials.apiHash.toByteArray()), Base64.NO_WRAP))
            .putBoolean(KEY_RESET, prefs.getBoolean(KEY_RESET, false) || changed && started.second != null)
            .commit()
    }

    override fun clear() {
        val fallback = builtIn()
        prefs.edit().remove(KEY_ID).remove(KEY_HASH)
            .putBoolean(KEY_RESET, prefs.getBoolean(KEY_RESET, false) || (started.second != null && fallback != started.second))
            .commit()
    }

    override fun restartApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Runtime.getRuntime().exit(0)
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        return c.doFinal(data, 12, data.size - 12)
    }

    private companion object {
        const val ALIAS = "yourstream_telegram_credentials"
        const val KEY_ID = "api_id"
        const val KEY_HASH = "api_hash"
        const val KEY_RESET = "reset_session_on_start"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialsModule {
    @Binds abstract fun credentials(impl: AndroidTelegramCredentialStore): TelegramCredentialStore
}
