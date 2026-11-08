package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.flow.Flow

/**
 * File access, including the range primitives playback streaming is built on (Phase 7):
 * [download] with offset/limit starts fetching at any position, [downloadedPrefixSize] says how
 * many bytes are on the device from a position, [readPart] reads them. No TDLib type leaks out.
 */
public interface TelegramFileRepository {
    /** Every file state change TDLib reports (downloads progressing, finishing, cancelled). */
    public val fileUpdates: Flow<TelegramFile>

    public suspend fun getFile(fileId: Int): AppResult<TelegramFile>

    /** Emits the file each time its download state changes. */
    public fun observeFile(fileId: Int): Flow<TelegramFile>

    /**
     * Starts (or re-prioritises) a download and returns immediately with the current state.
     * [priority] 1..32, higher downloads first.
     */
    public suspend fun download(fileId: Int, priority: Int = 1, offset: Long = 0, limit: Long = 0): AppResult<TelegramFile>

    public suspend fun cancelDownload(fileId: Int): AppResult<Unit>

    /**
     * Number of bytes available on the device starting exactly at [offset] (0 if the byte at
     * [offset] is not downloaded yet). Works for any offset, not only the current download window.
     */
    public suspend fun downloadedPrefixSize(fileId: Int, offset: Long): AppResult<Long>

    /**
     * Reads up to [count] downloaded bytes at [offset] through Telegram's file cache. Slower than
     * reading [TelegramFile.localPath] directly; used when the path cannot be read.
     */
    public suspend fun readPart(fileId: Int, offset: Long, count: Long): AppResult<ByteArray>

    /** Deletes the local copy (complete or partial) of a file. The remote file is untouched. */
    public suspend fun deleteLocalCopy(fileId: Int): AppResult<Unit>
}
