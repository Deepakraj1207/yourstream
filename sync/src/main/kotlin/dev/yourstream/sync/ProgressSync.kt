package dev.yourstream.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Clock
import dev.yourstream.telegram.api.TelegramSavedMessages
import dev.yourstream.telegram.api.TelegramUserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SyncScope

enum class SyncProblem {
    /** The passphrase differs from the one the other devices used. */
    WRONG_PASSPHRASE,
    NOT_SIGNED_IN,
    /** Telegram not reachable; the next sync retries. */
    NETWORK,
    FAILED,
}

data class SyncStatus(
    /** Off by default: progress stays on this device until the user turns sync on. */
    val enabled: Boolean = false,
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Device that wrote the snapshot read last ("Pixel 8"). */
    val lastFrom: String? = null,
    /** Units updated from other devices in the last sync. */
    val lastPulled: Int = 0,
    /** Units in the shared snapshot after the last sync. */
    val shared: Int = 0,
    val problem: SyncProblem? = null,
)

/** Where the sync key and settings live: app-private storage, the key wrapped by the Android Keystore. */
interface SyncSettings {
    var enabled: Boolean
    var key: ByteArray?
    var lastSyncAt: Long?
    var lastFrom: String?
    val deviceName: String
}

/**
 * Optional multi-device progress sync. When on, this device's watch progress and the encrypted
 * snapshot in the user's own Saved Messages are merged: newer progress of a unit wins, both ways.
 * Runs on start, a minute after watching, and on demand. When off (the default), nothing is
 * read from or written to Telegram.
 */
@Singleton
class ProgressSync @Inject constructor(
    private val store: ProgressSyncStore,
    private val saved: TelegramSavedMessages,
    private val users: TelegramUserRepository,
    private val settings: SyncSettings,
    private val clock: Clock,
    private val db: dev.yourstream.core.database.YourStreamDatabase,
    @SyncScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus(settings.enabled, lastSyncAt = settings.lastSyncAt, lastFrom = settings.lastFrom))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private var auto: Job? = null

    /** Starts the automatic sync (call once at app start). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (auto != null) return
        auto = scope.launch {
            if (settings.enabled) syncNow()
            db.progressSyncDao().observeLatestActivity().filterNotNull().distinctUntilChanged().drop(1)
                .debounce(AFTER_ACTIVITY_MS)
                .collect { if (settings.enabled) syncNow() }
        }
    }

    /**
     * Turns sync on with [passphrase] (the same on every device). If another device already shared
     * progress with a different passphrase, nothing is enabled and [SyncProblem.WRONG_PASSPHRASE]
     * is returned.
     */
    suspend fun enable(passphrase: CharArray): SyncStatus {
        val account = accountId() ?: return fail(SyncProblem.NOT_SIGNED_IN)
        val key = SyncCrypto.deriveKey(passphrase, account)
        passphrase.fill(' ')
        // Check the key against what is already there before storing anything.
        when (val remote = readRemote(key)) {
            is Remote.Problem -> return fail(remote.problem)
            else -> Unit
        }
        settings.key = key
        settings.enabled = true
        _status.update { it.copy(enabled = true, problem = null) }
        return syncNow()
    }

    /** Turns sync off. With [deleteShared] the encrypted snapshot is removed from Saved Messages too. */
    suspend fun disable(deleteShared: Boolean) {
        settings.enabled = false
        settings.key = null
        if (deleteShared) saved.delete(TAG)
        _status.value = SyncStatus(enabled = false, lastSyncAt = settings.lastSyncAt, lastFrom = settings.lastFrom)
    }

    suspend fun syncNow(): SyncStatus = mutex.withLock {
        val key = settings.key
        if (!settings.enabled || key == null) return _status.value
        _status.update { it.copy(running = true, problem = null) }
        val remote = when (val r = readRemote(key)) {
            is Remote.Problem -> return fail(r.problem)
            is Remote.Found -> r.snapshot
            Remote.None -> null
        }
        return try {
            val pulled = remote?.let { store.apply(it.records) } ?: 0
            val local = store.export()
            val union = ProgressMerge.union(remote?.records.orEmpty(), local)
            if (remote == null || union.toSet() != remote.records.toSet()) {
                val snapshot = ProgressSnapshot(device = settings.deviceName, at = clock.nowMillis(), records = union)
                val sent = saved.replace(TAG, FILE_NAME, CAPTION, SyncCrypto.seal(key, snapshot.encode()))
                if (sent is AppResult.Err) return fail(problemOf(sent.error))
            }
            val now = clock.nowMillis()
            settings.lastSyncAt = now
            settings.lastFrom = remote?.device
            SyncStatus(true, false, now, remote?.device, pulled, union.size).also { _status.value = it }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            fail(SyncProblem.FAILED)
        }
    }

    private sealed interface Remote {
        data object None : Remote
        data class Found(val snapshot: ProgressSnapshot) : Remote
        data class Problem(val problem: SyncProblem) : Remote
    }

    private suspend fun readRemote(key: ByteArray): Remote {
        val docs = when (val r = saved.find(TAG, 5)) { is AppResult.Ok -> r.value; is AppResult.Err -> return Remote.Problem(problemOf(r.error)) }
        val doc = docs.firstOrNull() ?: return Remote.None
        val bytes = when (val r = saved.read(doc)) { is AppResult.Ok -> r.value; is AppResult.Err -> return Remote.Problem(problemOf(r.error)) }
        return try {
            Remote.Found(ProgressSnapshot.decode(SyncCrypto.open(key, bytes)))
        } catch (e: SyncCrypto.WrongKey) {
            Remote.Problem(SyncProblem.WRONG_PASSPHRASE)
        } catch (e: Exception) {
            // Unreadable (damaged or a newer format): start a fresh snapshot rather than fail forever.
            Remote.None
        }
    }

    private suspend fun accountId(): Long? = users.currentUser.value?.id
        ?: withTimeoutOrNull(5_000) { users.currentUser.filterNotNull().first().id }
        ?: (users.refreshCurrentUser() as? AppResult.Ok)?.value?.id

    private fun problemOf(e: AppError) = when (e) {
        is AppError.Network -> SyncProblem.NETWORK
        is AppError.Unauthorized -> SyncProblem.NOT_SIGNED_IN
        else -> SyncProblem.FAILED
    }

    private fun fail(p: SyncProblem): SyncStatus = _status.updateAndGet { it.copy(running = false, problem = p) }

    private inline fun <T> MutableStateFlow<T>.updateAndGet(f: (T) -> T): T { update(f); return value }

    companion object {
        /** Marks the app's message in Saved Messages; nothing else there is read or changed. */
        const val TAG = "#yourstream_progress"
        const val FILE_NAME = "yourstream-progress.ysync"
        const val CAPTION = "$TAG · Encrypted watch progress from YourStream, used to continue on your other devices. Only YourStream with your sync passphrase can read it."
        const val AFTER_ACTIVITY_MS = 60_000L
    }
}

/** [SyncSettings] in app-private SharedPreferences; the key is encrypted with a non-exportable Keystore key. */
@Singleton
class AndroidSyncSettings @Inject constructor(@ApplicationContext context: Context) : SyncSettings {
    private val prefs = context.getSharedPreferences("progress_sync", Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    override var key: ByteArray?
        get() = prefs.getString("key", null)?.let { runCatching { unwrap(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }
        set(v) { prefs.edit().apply { if (v == null) remove("key") else putString("key", Base64.encodeToString(wrap(v), Base64.NO_WRAP)) }.apply() }

    override var lastSyncAt: Long?
        get() = prefs.getLong("last_sync_at", 0).takeIf { it > 0 }
        set(v) { prefs.edit().putLong("last_sync_at", v ?: 0).apply() }

    override var lastFrom: String?
        get() = prefs.getString("last_from", null)
        set(v) { prefs.edit().putString("last_from", v).apply() }

    override val deviceName: String = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL).filter { !it.isNullOrBlank() }
        .joinToString(" ").replaceFirstChar(Char::titlecase).ifEmpty { "Android" }

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

    private companion object { const val ALIAS = "yourstream_progress_sync" }
}
