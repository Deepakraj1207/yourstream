package dev.yourstream.account

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.playback.media3.ActivePlayback
import dev.yourstream.playback.media3.WebStreams
import dev.yourstream.playback.torrent.TorrentEngine
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.ui.screens.settings.DeviceStorage
import dev.yourstream.ui.screens.settings.StorageArea
import dev.yourstream.ui.screens.settings.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings → Storage: the caches' sizes are measured on disk (their directories are fixed: see
 * `AppModule`, `WebStreams`, `TorrentEngine`, `appImageLoader`); each is cleared through its
 * owner, so nothing is deleted behind a running component's back. Nothing is cleared while a
 * video plays.
 */
@Singleton
class AppStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: dagger.Lazy<TelegramFileRepository>,
    private val webStreams: dagger.Lazy<WebStreams>,
    private val torrents: dagger.Lazy<TorrentEngine>,
    private val playing: ActivePlayback,
) : DeviceStorage {
    private fun dir(area: StorageArea): File = when (area) {
        StorageArea.TELEGRAM -> File(context.cacheDir, "tdlib/files")
        StorageArea.WEB_STREAMS -> File(context.cacheDir, "stream-cache")
        StorageArea.TORRENTS -> File(context.cacheDir, "torrents")
        StorageArea.IMAGES -> File(context.cacheDir, "posters")
    }

    override suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        StorageUsage(
            bytes = StorageArea.entries.associateWith { sizeOf(dir(it)) },
            freeBytes = runCatching { freeSpace() }.getOrNull(),
        )
    }

    /** What apps can still use on this volume, counting cache data the system would clear for them. */
    private fun freeSpace(): Long {
        val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
        return manager.getAllocatableBytes(manager.getUuidForPath(context.cacheDir))
    }

    override suspend fun clear(area: StorageArea): AppResult<Unit> {
        if (playing.current.value != null) return AppResult.Err(AppError.InvalidState(context.getString(dev.yourstream.ui.screens.R.string.storage_busy)))
        return when (area) {
            StorageArea.TELEGRAM -> files.get().deleteAllLocalCopies()
            StorageArea.WEB_STREAMS -> attempt { webStreams.get().clearCache() }
            StorageArea.TORRENTS -> attempt { torrents.get().shutdown() }
            StorageArea.IMAGES -> attempt {
                coil3.SingletonImageLoader.get(context).let { it.memoryCache?.clear(); it.diskCache?.clear() }
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): AppResult<Unit> = try {
        withContext(Dispatchers.IO) { block() }
        AppResult.Ok(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Err(AppError.Unknown(e.message ?: e.javaClass.simpleName, e))
    }

    internal companion object {
        /** Total size of the files under [dir] (0 when it does not exist). */
        fun sizeOf(dir: File): Long = if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds abstract fun storage(impl: AppStorage): DeviceStorage
}
