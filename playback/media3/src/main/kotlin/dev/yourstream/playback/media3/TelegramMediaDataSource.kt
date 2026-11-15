package dev.yourstream.playback.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import dev.yourstream.playback.stream.StreamHints
import dev.yourstream.playback.stream.TelegramFileStream
import dev.yourstream.playback.stream.TelegramFileStreamer
import dev.yourstream.playback.stream.TelegramStreamException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Media3 [DataSource] over a Telegram file that is still downloading.
 *
 * Media3 opens a data source at a byte position (start, or wherever a seek or the MP4 index
 * points) and reads forward on a loader thread. Each open becomes a [TelegramFileStream] at that
 * position: the shared download window moves there, so seeking never fetches the bytes in
 * between. Reads block the loader thread until the bytes are on the device.
 *
 * Cancellation: Media3 interrupts the loader thread to cancel a load; the blocking wait aborts
 * immediately and surfaces as [InterruptedIOException], which Media3 treats as "cancelled".
 *
 * Errors are [DataSourceException]s with Media3 reason codes; the cause is the
 * [TelegramStreamException], which carries whether a retry can help.
 *
 * A file in raw pieces ([TelegramMediaUri.isSplit]) is read as the one file the pieces make up:
 * a position maps to its piece ([SplitLayout]), reading continues into the next piece at each
 * boundary, and the next piece starts downloading [NEXT_PIECE_AHEAD_BYTES] before the boundary so
 * playback does not wait there.
 */
@OptIn(UnstableApi::class)
class TelegramMediaDataSource(private val streamer: TelegramFileStreamer) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var stream: TelegramFileStream? = null
    private var bytesRemaining = 0L
    private var opened = false

    // Split files: the pieces, the one being read, how much of it is left, and the next one, opened early.
    private var split: TelegramMediaUri? = null
    private var layout: SplitLayout? = null
    private var piece = 0
    private var pieceRemaining = 0L
    private var next: TelegramFileStream? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val ref = TelegramMediaUri.parse(dataSpec.uri)
            ?: throw DataSourceException(IOException("not a Telegram URI: ${dataSpec.uri}"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        transferInitializing(dataSpec)
        if (ref.isSplit) return openSplit(dataSpec, ref)
        val s = blocking { streamer.open(ref.fileId, dataSpec.position, ref.hints) }
        stream = s
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            s.size >= 0 -> s.size - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openSplit(dataSpec: DataSpec, ref: TelegramMediaUri): Long {
        val l = SplitLayout(ref.parts.map { it.second })
        if (dataSpec.position > l.total) {
            throw DataSourceException(IOException("position ${dataSpec.position} beyond ${l.total}"), PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        split = ref; layout = l
        val (index, inPiece) = l.locate(dataSpec.position)
        openPiece(index, inPiece)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else l.total - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /** Reads piece [index] from [inPiece] (using the stream opened early when it is that piece). */
    private fun openPiece(index: Int, inPiece: Long) {
        val ref = split!!
        val (id, size) = ref.parts[index]
        val early = next?.takeIf { it.fileId == id && inPiece == 0L && it.position == 0L }
        next = if (early != null) null else next?.let { it.close(); null }
        stream?.close()
        stream = early ?: blocking { streamer.open(id, inPiece, StreamHints(size, null)) }
        piece = index
        pieceRemaining = size - inPiece
        SplitReads.reading(ref.fileId, id)
    }

    private fun readSplit(buffer: ByteArray, offset: Int, length: Int): Int {
        val ref = split!!
        while (pieceRemaining == 0L) {
            if (piece == ref.parts.lastIndex) return C.RESULT_END_OF_INPUT
            openPiece(piece + 1, 0)
        }
        val s = stream ?: throw IllegalStateException("not opened")
        val want = minOf(length.toLong(), pieceRemaining, if (bytesRemaining == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else bytesRemaining).toInt()
        val n = blocking { s.read(buffer, offset, want) }
        if (n < 0) {
            throw DataSourceException(IOException("piece ${piece + 1} ended $pieceRemaining bytes early"), PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        pieceRemaining -= n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        // Close to the end of this piece: start downloading the next one.
        if (pieceRemaining <= NEXT_PIECE_AHEAD_BYTES && next == null && piece < ref.parts.lastIndex) {
            val (id, size) = ref.parts[piece + 1]
            next = blocking { streamer.open(id, 0, StreamHints(size, null)).also { it.prefetch() } }
        }
        return n
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (split != null) return readSplit(buffer, offset, length)
        val s = stream ?: throw IllegalStateException("not opened")
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = blocking { s.read(buffer, offset, toRead) }
        if (n < 0) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                throw DataSourceException(IOException("file ended $bytesRemaining bytes early"), PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        stream?.close()
        stream = null
        next?.close()
        next = null
        split = null
        layout = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Runs a suspending stream call on the (loader) thread; interruption cancels it. */
    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Telegram read cancelled")
    } catch (e: TelegramStreamException) {
        throw DataSourceException(e, reasonFor(e))
    }

    class Factory(private val streamer: TelegramFileStreamer) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramMediaDataSource(streamer)
    }

    companion object {
        /** How long before the end of a piece the next piece starts downloading. */
        const val NEXT_PIECE_AHEAD_BYTES = 8L * 1024 * 1024

        fun reasonFor(e: TelegramStreamException): Int = when (e) {
            is TelegramStreamException.Stalled -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            is TelegramStreamException.Unavailable -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            is TelegramStreamException.OutOfRange -> PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            is TelegramStreamException.Telegram ->
                if (e.retryable) PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED else PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        }
    }
}
