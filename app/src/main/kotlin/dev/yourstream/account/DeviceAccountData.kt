package dev.yourstream.account

import android.content.Context
import androidx.core.content.edit
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.core.common.Logger
import dev.yourstream.core.database.AccountData
import dev.yourstream.core.database.OpenedDatabase
import dev.yourstream.media.indexer.work.IndexScheduler
import dev.yourstream.playback.media3.WebStreams
import dev.yourstream.playback.torrent.TorrentEngine
import dev.yourstream.robust.AppDiagnostics
import dev.yourstream.sync.ProgressSync
import dev.yourstream.ui.screens.settings.AccountDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a signed-in account leaves on this device, and how it is removed on sign-out
 * ([dev.yourstream.ui.screens.settings.AccountSession]). TDLib deletes its own session and
 * downloaded files when it logs out; this covers everything else.
 *
 * Removed: indexed files, library, metadata matches, watch history and progress, track choices,
 * search index ([AccountData]); indexing work; posters and thumbnails; cached web and torrent
 * streams; the progress-sync key; logs, events and crash reports (they can name chats and titles).
 * Kept (device settings): app settings, language preferences, add-ons, the TMDB key.
 */
@Singleton
class DeviceAccountData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: dagger.Lazy<OpenedDatabase>,
    private val webStreams: dagger.Lazy<WebStreams>,
    private val torrents: dagger.Lazy<TorrentEngine>,
    private val progressSync: dagger.Lazy<ProgressSync>,
    private val logger: Logger,
) : AccountDataStore {
    private val prefs = context.getSharedPreferences("account_session", Context.MODE_PRIVATE)

    override var signedOutNotice: Boolean
        get() = prefs.getBoolean(KEY_NOTICE, false)
        set(value) { prefs.edit { putBoolean(KEY_NOTICE, value) } }

    override suspend fun hasAccountData(): Boolean = AccountData.hasAccountData(database.get().db)

    override suspend fun wipe() {
        // Stop indexing first, so nothing writes the old account's rows back.
        step("indexing work") { WorkManager.getInstance(context).cancelAllWorkByTag(IndexScheduler.TAG) }
        step("database") { AccountData.wipe(database.get().db) }
        step("progress sync") { progressSync.get().disable(deleteShared = false) }
        step("images") {
            coil3.SingletonImageLoader.get(context).let { it.memoryCache?.clear(); it.diskCache?.clear() }
        }
        step("web stream cache") { webStreams.get().clearCache() }
        step("torrents") { torrents.get().shutdown() }
        step("logs") {
            val d = AppDiagnostics.get(context)
            d.log.clear(); d.events.clear(); d.crashes.clear(); d.playbackSession.clear()
        }
    }

    /** Each part on its own: one failing never keeps the others. The database part rethrows (it is retried). */
    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (name == "database") throw e
            logger.w(TAG, "sign-out cleanup: $name failed", e)
        }
    }

    private companion object {
        const val TAG = "AccountData"
        const val KEY_NOTICE = "signed_out_notice"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountDataModule {
    @Binds abstract fun accountData(impl: DeviceAccountData): AccountDataStore
}
