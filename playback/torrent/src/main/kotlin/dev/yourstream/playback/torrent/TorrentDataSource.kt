package dev.yourstream.playback.torrent

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** `torrent://<info hash>/<file name>`: the file name lets Media3 pick the container's extractor first. */
object TorrentUri {
    const val SCHEME = "torrent"

    fun of(key: String, fileName: String): Uri = Uri.Builder().scheme(SCHEME).authority(key.lowercase()).appendPath(fileName).build()

    fun key(uri: Uri): String? = uri.authority?.takeIf { uri.scheme == SCHEME && it.isNotEmpty() }
}

/**
 * Media3 [DataSource] over a torrent file that is still downloading. Media3 opens it at a byte
 * position (the start, a seek target, the container index at the end) and reads forward on a
 * loader thread; each read blocks until its piece is on the device ([PieceSource.awaitReadable]),
 * which also moves that piece to the front of the download queue.
 *
 * Cancelling a load interrupts the loader thread: the wait ends at once as [InterruptedIOException].
 */
@OptIn(UnstableApi::class)
class TorrentDataSource(private val sources: (String) -> PieceSource?) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(private val sources: (String) -> PieceSource?) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(sources)
    }

    private var uri: Uri? = null
    private var source: PieceSource? = null
    private var file: RandomAccessFile? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val key = TorrentUri.key(dataSpec.uri)
            ?: throw DataSourceException(IOException("not a torrent URI: ${dataSpec.uri}"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val s = sources(key)
            ?: throw DataSourceException(IOException("the torrent stream is closed"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        transferInitializing(dataSpec)
        if (dataSpec.position > s.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        source = s
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, s.size - position) else s.size - position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val s = source ?: throw IllegalStateException("not opened")
        var attempts = 0
        while (true) {
            val readable = try {
                s.awaitReadable(position)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("torrent read cancelled")
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            val want = minOf(length.toLong(), readable, bytesRemaining).toInt()
            val n = try {
                val f = file ?: RandomAccessFile(s.file, "r").also { file = it }
                f.seek(position)
                f.read(buffer, offset, want)
            } catch (e: FileNotFoundException) {
                -1 // created with the first write; it is there a moment later
            } catch (e: IOException) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
            if (n > 0) {
                position += n
                bytesRemaining -= n
                bytesTransferred(n)
                return n
            }
            // The piece is verified but its bytes have not reached the file yet: try again shortly.
            if (++attempts > 150) throw DataSourceException(IOException("downloaded data could not be read from ${s.file.name}"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("torrent read cancelled")
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        source = null
        try {
            file?.close()
        } catch (_: IOException) {
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
