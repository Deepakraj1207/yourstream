package dev.yourstream.desktop.data

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.desktop.library.PosterService
import dev.yourstream.desktop.update.DesktopUpdates
import dev.yourstream.telegram.api.TelegramFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the Windows app keeps on disk that can be cleared (Settings → Storage). */
enum class CacheArea(val label: String, val detail: String) {
    TELEGRAM("Telegram videos", "Parts of videos you streamed (trimmed above 2 GB); they stream again when you play them"),
    POSTERS("Posters and backdrops", "They download again when shown; which poster belongs to which title is kept"),
    UPDATES("Downloaded updates", "Installers the app downloaded to update itself"),
}

/**
 * Measures and clears the Windows app's caches. Each is cleared through its owner: Telegram's
 * files through TDLib (never deleted behind its back), poster images through [PosterService],
 * installers through [DesktopUpdates] (not while one downloads). Clearing never touches the
 * Telegram session, the index of your chats, settings or watch positions. The app shows Settings
 * only when no video plays, so nothing is cleared under the player; [beforeTelegram] stops the
 * title page's warm-up download first.
 */
class DesktopStorage(
    private val dataDir: File,
    private val files: TelegramFileRepository,
    private val posters: PosterService,
    private val updates: DesktopUpdates,
    private val beforeTelegram: () -> Unit,
    private val logger: Logger,
) {
    /** Bytes per area, measured on disk. */
    suspend fun usage(): Map<CacheArea, Long> = withContext(Dispatchers.IO) {
        CacheArea.entries.associateWith { area ->
            when (area) {
                CacheArea.TELEGRAM -> sizeOf(File(dataDir, "telegram/files"))
                CacheArea.POSTERS -> posters.imageBytes()
                CacheArea.UPDATES -> sizeOf(File(dataDir, "updates"))
            }
        }
    }

    suspend fun clear(area: CacheArea): AppResult<Unit> {
        val result = when (area) {
            CacheArea.TELEGRAM -> { beforeTelegram(); files.deleteAllLocalCopies() }
            CacheArea.POSTERS -> try { posters.clearImages(); AppResult.Ok(Unit) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { AppResult.Err(AppError.Unknown(e.message ?: "could not delete", e)) }
            CacheArea.UPDATES -> if (withContext(Dispatchers.IO) { updates.clearDownloads() }) AppResult.Ok(Unit)
                else AppResult.Err(AppError.InvalidState("an update is downloading; try again when it is done"))
        }
        logger.i(TAG, "cleared ${area.name}: ${if (result is AppResult.Ok) "ok" else "failed"}")
        return result
    }

    /** Clears every area that holds something; what could not be cleared, with the reason. */
    suspend fun clearAll(): Map<CacheArea, String> {
        val before = usage()
        val failed = LinkedHashMap<CacheArea, String>()
        for (area in CacheArea.entries) {
            if ((before[area] ?: 0L) == 0L) continue
            (clear(area) as? AppResult.Err)?.let { failed[area] = it.error.message }
        }
        return failed
    }

    companion object {
        private const val TAG = "Storage"

        /** Total size of the files under [dir] (0 when it does not exist). */
        fun sizeOf(dir: File): Long = if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        /** 1536 → "1.5 KB"; 3_221_225_472 → "3.0 GB". */
        fun sizeText(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.0f MB".format(java.util.Locale.ROOT, bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "%.0f KB".format(java.util.Locale.ROOT, bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
