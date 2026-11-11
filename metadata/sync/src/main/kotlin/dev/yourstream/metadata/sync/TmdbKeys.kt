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

/**
 * The TMDB key: the one the user entered in Settings (stored on the device only), else the one
 * built into the release, else none (Cinemeta alone). Read on every TMDB request, so a change
 * applies at once.
 */
@Singleton
class TmdbKeys @Inject constructor(@ApplicationContext context: Context, private val builtIn: TmdbConfig) {
    private val prefs = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE)
    private val _userKey = MutableStateFlow(prefs.getString(KEY, null)?.takeIf { it.isNotBlank() })
    val userKey: StateFlow<String?> = _userKey.asStateFlow()

    val source: TmdbKeySource
        get() = when {
            _userKey.value != null -> TmdbKeySource.USER
            builtIn.isConfigured -> TmdbKeySource.BUILT_IN
            else -> TmdbKeySource.NONE
        }

    fun current(): TmdbConfig = _userKey.value?.let(builtIn::withKey) ?: builtIn

    internal fun store(key: String?) {
        prefs.edit().apply { if (key == null) remove(KEY) else putString(KEY, key) }.apply()
        _userKey.value = key
    }

    private companion object { const val KEY = "user_key" }
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
