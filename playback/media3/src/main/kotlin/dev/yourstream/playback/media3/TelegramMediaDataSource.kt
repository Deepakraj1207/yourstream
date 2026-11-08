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
 */
@OptIn(UnstableApi::class)
class TelegramMediaDataSource(private val streamer: TelegramFileStreamer) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var stream: TelegramFileStream? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val ref = TelegramMediaUri.parse(dataSpec.uri)
            ?: throw DataSourceException(IOException("not a Telegram URI: ${dataSpec.uri}"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        transferInitializing(dataSpec)
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

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
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
        fun reasonFor(e: TelegramStreamException): Int = when (e) {
            is TelegramStreamException.Stalled -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            is TelegramStreamException.Unavailable -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            is TelegramStreamException.OutOfRange -> PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            is TelegramStreamException.Telegram ->
                if (e.retryable) PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED else PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        }
    }
}
