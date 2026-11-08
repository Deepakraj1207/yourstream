package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.flow.Flow

/**
 * Basic file access. Phase 1 only covers lookup and whole-file downloads (e.g. avatars);
 * ranged streaming for playback is Phase 2 and will build on [download] with offsets.
 */
public interface TelegramFileRepository {
    public suspend fun getFile(fileId: Int): AppResult<TelegramFile>

    /** Emits the file each time its download state changes. */
    public fun observeFile(fileId: Int): Flow<TelegramFile>

    /**
     * Starts (or re-prioritises) a download and returns immediately with the current state.
     * [priority] 1..32, higher downloads first.
     */
    public suspend fun download(fileId: Int, priority: Int = 1, offset: Long = 0, limit: Long = 0): AppResult<TelegramFile>

    public suspend fun cancelDownload(fileId: Int): AppResult<Unit>
}
