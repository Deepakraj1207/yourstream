package dev.yourstream.playback.stream

import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramFileRepository
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads bytes that Telegram already reported as downloaded.
 *
 * Fast path: the partially downloaded file itself (Telegram writes every part at its real
 * offset), read with [RandomAccessFile]. Not a FileChannel on purpose: channels close themselves
 * when the reading thread is interrupted, which players do on every cancelled load. Slow path:
 * Telegram's readFilePart, when the path is unknown or unreadable.
 *
 * Owned by one stream; not thread-safe.
 */
internal class LocalPartReader(
    private val fileId: Int,
    private val repo: TelegramFileRepository,
    private val config: StreamingConfig,
) {
    private var path: String? = null
    private var raf: RandomAccessFile? = null
    private var directBroken = !config.directFileReads

    var directReads = 0L; private set
    var partReads = 0L; private set

    /** Reads up to [length] bytes known to be downloaded at [position]; returns the count (> 0). */
    suspend fun read(currentPath: String?, position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (!directBroken && currentPath != null) {
            try {
                val n = direct(currentPath, position, buffer, offset, length)
                if (n > 0) { directReads++; return n }
            } catch (e: IOException) {
                // The temp file was moved/removed (download completed, storage optimised): retry once with
                // a fresh handle next time, use the Telegram API now.
                closeFile()
            }
        }
        return viaTelegram(position, buffer, offset, length)
    }

    private fun direct(currentPath: String, position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (currentPath != path) {
            closeFile()
            val f = File(currentPath)
            if (!f.canRead()) throw IOException("cannot read $currentPath")
            raf = RandomAccessFile(f, "r")
            path = currentPath
        }
        val file = raf!!
        if (position >= file.length()) return 0 // sparse tail not materialised yet
        file.seek(position)
        return file.read(buffer, offset, length).coerceAtLeast(0)
    }

    private suspend fun viaTelegram(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val count = minOf(length, config.maxReadPartBytes)
        return when (val r = repo.readPart(fileId, position, count.toLong())) {
            is AppResult.Ok -> {
                val bytes = r.value
                if (bytes.isEmpty()) throw IOException("readFilePart returned no data at $position for file $fileId")
                val n = minOf(bytes.size, length)
                System.arraycopy(bytes, 0, buffer, offset, n)
                partReads++
                n
            }
            is AppResult.Err -> throw TelegramStreamException.Telegram(fileId, r.error)
        }
    }

    private fun closeFile() {
        runCatching { raf?.close() }
        raf = null
        path = null
    }

    fun close() = closeFile()
}
