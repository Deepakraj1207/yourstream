package dev.yourstream.playback.torrent

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Milliseconds on a monotonic clock (timeouts and settle times; not wall time). */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/** Where a torrent stream is. */
enum class TorrentPhase {
    /** Starting the engine. */
    STARTING,
    /** Asking peers for the torrent's file list. */
    METADATA,
    /** Downloading what the player needs next. */
    BUFFERING,
    /** What the player needs next is on the device; the rest keeps downloading. */
    STREAMING,
    /** The whole file is on the device. */
    COMPLETE,
}

/** What the player shows while a torrent streams. */
data class TorrentStats(
    val phase: TorrentPhase = TorrentPhase.STARTING,
    val peers: Int = 0,
    val seeds: Int = 0,
    val downloadBps: Long = 0,
    val uploadBps: Long = 0,
    /** Share of the file on the device (0..1). */
    val progress: Float = 0f,
    /** Bytes ready ahead of where the player reads. */
    val readyAhead: Long = 0,
    val fileName: String? = null,
    val fileSize: Long = 0,
)

/** A torrent problem the user can understand; [retryable] when trying again may help. */
class TorrentException(message: String, val retryable: Boolean = true) : IOException(message)

/** The bytes of one file that is still downloading: what [TorrentDataSource] reads. */
interface PieceSource {
    val key: String
    val size: Long
    val file: File

    /**
     * Blocks until the byte at [position] is on the device, then returns how many bytes from
     * there can be read right away (at least 1). Interruptible.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun awaitReadable(position: Long): Long
}

/**
 * One file of a torrent being streamed. The engine keeps the pieces around [readPosition]
 * downloading first (see [TorrentEngine]); readers block in [awaitReadable] until the piece they
 * need is verified, which also moves that piece to the front of the queue.
 */
class TorrentStream internal constructor(
    override val key: String,
    internal val handle: TorrentHandle,
    override val file: File,
    val fileName: String,
    internal val fileIndex: Int,
    val map: PieceMap,
    private val noDataTimeoutMs: Long,
) : PieceSource {
    override val size: Long get() = map.fileSize

    @Volatile internal var closed = false
    /** Where the player last read: the download window follows it. */
    @Volatile internal var readPosition = 0L
    /** Last time any payload arrived ([monotonicMs]). */
    @Volatile internal var lastActivity = monotonicMs()
    internal var jobs: Job? = null

    /** Pieces a reader is blocked on: always fetched first. */
    internal val waiting: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** When each piece passed its hash check, to give its last writes time to reach the file. */
    private val finishedAt = ConcurrentHashMap<Int, Long>()

    internal val mutableStats = MutableStateFlow(TorrentStats(TorrentPhase.BUFFERING, fileName = fileName, fileSize = map.fileSize))
    val stats: StateFlow<TorrentStats> = mutableStats.asStateFlow()

    internal fun onPieceFinished(piece: Int) {
        val now = monotonicMs()
        finishedAt[piece] = now
        lastActivity = now
    }

    internal fun have(piece: Int): Boolean = !closed && runCatching { handle.havePiece(piece) }.getOrDefault(false)

    override fun awaitReadable(position: Long): Long {
        readPosition = position
        val piece = map.pieceAt(position)
        val started = monotonicMs()
        var asked = false
        while (true) {
            if (closed) throw TorrentException("The torrent stream was closed", retryable = false)
            if (have(piece)) {
                waiting.remove(piece)
                settle(piece)
                return map.readableFrom(position, ::have).coerceAtLeast(1)
            }
            if (!asked) {
                asked = true
                waiting += piece
                urgent(piece)
            }
            Thread.sleep(POLL_MS)
            val now = monotonicMs()
            if (now - started > noDataTimeoutMs && now - lastActivity > noDataTimeoutMs) {
                waiting.remove(piece)
                throw TorrentException("No peer sent any data for ${noDataTimeoutMs / 1000} seconds.")
            }
        }
    }

    /**
     * A piece counts as present once its hash check passes, which can be a moment before the
     * disk thread has written its last block to the file: wait that moment out.
     */
    private fun settle(piece: Int) {
        val at = finishedAt[piece] ?: return
        val wait = SETTLE_MS - (monotonicMs() - at)
        if (wait > 0) Thread.sleep(wait)
    }

    /** [piece] (and the two after it) to the front of the queue: top priority, deadline now. */
    private fun urgent(piece: Int) {
        runCatching {
            for (k in 0..2) {
                val p = piece + k
                if (p > map.lastPiece || have(p)) continue
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                handle.setPieceDeadline(p, k * 300)
            }
        }
    }

    private companion object {
        const val POLL_MS = 40L
        const val SETTLE_MS = 400L
    }
}
