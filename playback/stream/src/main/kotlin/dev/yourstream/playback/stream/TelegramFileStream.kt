package dev.yourstream.playback.stream

import java.io.Closeable

/**
 * A positioned, forward-reading view of a Telegram file that works while the file is being
 * downloaded. Seeking = closing and opening a new stream at the new position (what Media3 does);
 * the shared download window then moves to the new position.
 *
 * Suspending and cancellable: cancelling the caller (or interrupting a thread blocked in
 * `runBlocking`) aborts a wait immediately. Used by one reader at a time.
 */
class TelegramFileStream internal constructor(
    private val session: TelegramFileStreamer.FileSession,
    startPosition: Long,
) : Closeable {
    val fileId: Int get() = session.fileId

    /** Total size in bytes, or -1 when Telegram does not know it. */
    val size: Long get() = session.size

    var position: Long = startPosition
        private set

    /** Exclusive end of the bytes known to be on the device from [position]. */
    private var availableEnd = startPosition
    private val reader = LocalPartReader(session.fileId, session.repository, session.streamingConfig)
    private var closed = false
    private val openedAt = session.clock.nanos()

    /** Nanoseconds from open to the first byte (null until then): startup / seek latency of this stream. */
    var timeToFirstByteNanos: Long? = null
        private set

    /**
     * Reads up to [length] bytes into [buffer]. Returns the number read (> 0), or -1 at the end of
     * the file. Suspends while the data is being downloaded.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "stream closed" }
        if (length == 0) return 0
        val size = size
        if (size >= 0 && position >= size) return -1
        if (position >= availableEnd) {
            val available = session.awaitData(position)
            if (available <= 0) return -1
            availableEnd = position + available
        }
        val n = minOf(length.toLong(), availableEnd - position).toInt()
        val read = reader.read(session.file.localPath, position, buffer, offset, n)
        if (timeToFirstByteNanos == null) timeToFirstByteNanos = session.clock.nanos() - openedAt
        position += read
        session.bytesServed.addAndGet(read.toLong())
        // Keep the download window ahead of where the reader is now.
        session.maintainWindow(position, availableEnd)
        return read
    }

    /** Fills [buffer] completely unless the file ends first. Returns the bytes read. */
    suspend fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
        var total = 0
        while (total < length) {
            val n = read(buffer, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    override fun close() {
        if (closed) return
        closed = true
        session.directReads.addAndGet(reader.directReads)
        session.partReads.addAndGet(reader.partReads)
        reader.close()
        session.openStreams.decrementAndGet()
    }
}
