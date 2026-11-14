package dev.yourstream.ui.screens.settings

import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything on the device that belongs to the signed-in account, outside TDLib (which deletes
 * its own session and files when it logs out). Implemented in the app module, where every
 * store is reachable.
 */
interface AccountDataStore {
    /** True while any account data is on the device (library, history, caches…). */
    suspend fun hasAccountData(): Boolean

    /** Removes it: indexed files, library, watch history, matches, search index, caches, sync key, logs. Device settings stay. */
    suspend fun wipe()

    /** "You were signed out" waiting to be shown (kept across restarts). */
    var signedOutNotice: Boolean
}

/**
 * Sign-out and its cleanup. One rule: **while Telegram is signed out, no account data stays on
 * the device.**
 *
 * - Logging out (by the user, or the session ended elsewhere: terminated from another device,
 *   expired, account deleted) shows as [AuthState.LoggingOut]; the account data is wiped then.
 * - Whenever the state is [AuthState.WaitPhoneNumber] (signed out, e.g. at start), leftover data
 *   is wiped too: from versions before 0.11 that kept it, or a wipe cut short by the app being
 *   killed. Every sign-in passes through that state, so a second account never sees the first
 *   one's library.
 * - A sign-out the user did not ask for leaves [notice] set until the next sign-in, so the
 *   sign-in screen can say why the library is gone.
 */
@Singleton
class AccountSession @Inject constructor(
    private val auth: TelegramAuthManager,
    private val data: AccountDataStore,
    private val logger: Logger,
) {
    private val _notice = MutableStateFlow(false)
    /** The session ended without the user signing out (shown on the sign-in screen). */
    val notice: StateFlow<Boolean> = _notice.asStateFlow()

    private val _wiping = MutableStateFlow(false)
    /** Account data is being removed right now. */
    val wiping: StateFlow<Boolean> = _wiping.asStateFlow()

    @Volatile private var userSignedOut = false
    private val lock = Mutex()
    private var job: Job? = null

    /** Starts watching the sign-in state (once, from the application). */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        _notice.value = data.signedOutNotice
        job = scope.launch {
            // The state flow may skip the brief LoggingOut state; "was signed in, now signed out"
            // is what counts. A restart (Ready → Closed → Ready) is neither.
            var signedIn = false
            auth.state.collect { state ->
                when (state) {
                    AuthState.LoggingOut, AuthState.WaitPhoneNumber -> {
                        if (signedIn && !userSignedOut) setNotice(true)
                        signedIn = false
                        wipe(if (state == AuthState.LoggingOut) "signed out" else "signed out, data left", force = state == AuthState.LoggingOut)
                    }
                    AuthState.Ready -> {
                        signedIn = true
                        userSignedOut = false
                        setNotice(false)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** The user signs out: no "you were signed out" notice afterwards. */
    suspend fun signOut(): AppResult<Unit> {
        userSignedOut = true
        return auth.logOut().also { if (it is AppResult.Err) userSignedOut = false }
    }

    fun dismissNotice() = setNotice(false)

    private fun setNotice(on: Boolean) {
        data.signedOutNotice = on
        _notice.value = on
    }

    private suspend fun wipe(reason: String, force: Boolean) = lock.withLock {
        try {
            if (!force && !data.hasAccountData()) return@withLock
            _wiping.value = true
            logger.i(TAG, "removing account data ($reason)")
            data.wipe()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Tried again on the next signed-out state (every start while signed out).
            logger.e(TAG, "removing account data failed", e)
        } finally {
            _wiping.value = false
        }
    }

    private companion object { const val TAG = "AccountSession" }
}
