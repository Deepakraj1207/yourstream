package dev.yourstream.playback.torrent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams torrents the way Stremio's streaming server does, built on libtorrent:
 *
 * - The session starts on the first stream and stops a while after the last one closes.
 * - A stream asks peers (trackers, DHT) for the torrent's file list, picks the file to play and
 *   downloads only what playback needs: a window of pieces from the player's read position
 *   ([TorrentPlan.readAheadBytes]), the file's end (container index), and anything a reader is
 *   blocked on. The next pieces carry staggered deadlines so they arrive in order.
 * - Data lives in the app's cache folder and is deleted when the stream closes.
 */
@Singleton
class TorrentEngine(
    /** Where torrent data lives while it plays (the app's cache folder). */
    private val root: File,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "torrents"))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val streams = ConcurrentHashMap<String, TorrentStream>()

    @Volatile private var session: SessionManager? = null
    private var idleStop: Job? = null

    /** The open stream for [key] (an info hash), for the data source. */
    fun source(key: String): PieceSource? = streams[key.lowercase()]?.takeIf { !it.closed }

    /**
     * Starts streaming [request]: suspends while peers send the file list (reporting through
     * [onStats]), then returns the stream, already downloading its first pieces.
     */
    suspend fun open(request: TorrentRequest, onStats: (TorrentStats) -> Unit = {}): TorrentStream = withContext(Dispatchers.IO) {
        if (!TorrentPlan.isInfoHash(request.infoHash)) throw TorrentException("This is not a torrent.", retryable = false)
        lock.withLock {
            idleStop?.cancel()
            onStats(TorrentStats(TorrentPhase.STARTING))
            val s = session()
            val hash = Sha1Hash.parseHex(request.key)
            streams[request.key]?.let { closeNow(it) }
            // The same torrent closed a moment ago may still be on its way out of the session.
            withTimeoutOrNull(5_000) { while (s.find(hash)?.isValid == true) delay(100) }
            val dir = File(root, request.key).apply { mkdirs() }
            if (root.usableSpace < MIN_FREE_BYTES) throw TorrentException("Not enough free space on the device to stream (at least ${MIN_FREE_BYTES shr 20} MB needed).", retryable = false)

            s.download(TorrentPlan.magnet(request), dir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
            val handle = withTimeoutOrNull(15_000) {
                var h: TorrentHandle? = null
                while (h == null) {
                    h = s.find(hash)?.takeIf { it.isValid }
                    if (h == null) delay(100)
                }
                h
            } ?: throw TorrentException("The torrent engine could not start this torrent.")
            try {
                // Not queued behind other torrents: this one plays now.
                handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
                handle.resume()
                awaitMetadata(handle, onStats)
                val stream = prepare(request, handle, dir)
                // The played file must not stay at priority 0: libtorrent keeps the pieces of such
                // files in a hidden part file, not in the file itself. Which of its pieces download
                // is steered piece by piece (steer).
                handle.filePriority(stream.fileIndex, Priority.DEFAULT)
                streams[request.key] = stream
                stream.jobs = scope.launch {
                    launch { steer(stream) }
                    launch { watch(stream) }
                }
                stream
            } catch (e: Throwable) {
                runCatching { s.remove(handle, SessionHandle.DELETE_FILES) }
                dir.deleteRecursively()
                scheduleStop()
                throw e
            }
        }
    }

    /** Stops [stream] and deletes its data; the session stops later if nothing else plays. */
    fun close(stream: TorrentStream) {
        if (stream.closed) return
        stream.closed = true
        stream.jobs?.cancel()
        // Stop transferring at once; removal waits for the engine lock (another stream may be starting).
        scope.launch { runCatching { stream.handle.pause() } }
        scope.launch { lock.withLock { closeNow(stream) } }
    }

    private fun closeNow(stream: TorrentStream) {
        stream.closed = true
        stream.jobs?.cancel()
        streams.remove(stream.key, stream)
        runCatching { session?.remove(stream.handle, SessionHandle.DELETE_FILES) }
        scope.launch {
            delay(2_000) // libtorrent closes its files first
            lock.withLock { if (streams[stream.key] == null) File(root, stream.key).deleteRecursively() }
        }
        scheduleStop()
    }

    private suspend fun awaitMetadata(handle: TorrentHandle, onStats: (TorrentStats) -> Unit) {
        val start = monotonicMs()
        while (true) {
            val st = handle.status()
            if (st.hasMetadata()) return
            onStats(TorrentStats(TorrentPhase.METADATA, peers = st.numPeers(), seeds = st.numSeeds(), downloadBps = st.downloadPayloadRate().toLong()))
            if (monotonicMs() - start > METADATA_TIMEOUT_MS) {
                throw TorrentException(
                    if (st.numPeers() == 0) "No one is sharing this torrent right now. Try another stream."
                    else "Peers did not send this torrent's file list. Try another stream.",
                )
            }
            delay(250)
        }
    }

    private fun prepare(request: TorrentRequest, handle: TorrentHandle, dir: File): TorrentStream {
        val info = handle.torrentFile() ?: throw TorrentException("Peers did not send this torrent's file list.")
        val fs = info.files()
        val files = (0 until fs.numFiles()).filterNot { fs.padFileAt(it) }.map { TorrentFileEntry(it, fs.filePath(it), fs.fileSize(it)) }
        val chosen = TorrentPlan.pickFile(files, request.fileIdx, request.fileName)
            ?: throw TorrentException("This torrent has no files.", retryable = false)
        if (chosen.extension in ARCHIVES) {
            throw TorrentException("This torrent holds a .${chosen.extension} archive, not a video. Try another stream.", retryable = false)
        }
        val needed = TorrentPlan.spaceNeeded(chosen.size)
        if (root.usableSpace < needed) {
            throw TorrentException("Not enough free space on the device: about ${needed shr 20} MB needed.", retryable = false)
        }
        val map = PieceMap(fs.fileOffset(chosen.index), chosen.size, info.pieceLength(), info.numPieces())
        return TorrentStream(request.key, handle, File(fs.filePath(chosen.index, dir.absolutePath)), chosen.name, chosen.index, map, NO_DATA_TIMEOUT_MS)
    }

    /**
     * Keeps libtorrent fetching what the player needs: the read-ahead window from the read
     * position, the file's end, and pieces readers wait for, all top priority, everything else
     * not at all; the next missing pieces get staggered deadlines. Re-planned when the read
     * position reaches another piece or a reader starts waiting.
     */
    private suspend fun steer(s: TorrentStream) {
        val ahead = TorrentPlan.readAheadBytes(s.map.fileSize)
        val deadlines = ((24L shl 20) / s.map.pieceLength).toInt().coerceIn(3, 16)
        val tail = s.map.tail()
        var lastStart = -1
        var lastWaiting = emptySet<Int>()
        var lastMissing = -1
        while (scope.isActive && !s.closed) {
            runCatching {
                // One snapshot of which pieces are present (a single call into libtorrent).
                val pieces = s.handle.status(TorrentHandle.QUERY_PIECES).pieces()
                val have = { p: Int -> p < pieces.size() && pieces.getBit(p) }
                val window = s.map.window(s.readPosition, ahead)
                val waiting = s.waiting.toSet()
                val missing = window.firstOrNull { !have(it) } ?: -1
                if (window.first != lastStart || waiting != lastWaiting || missing != lastMissing) {
                    val priorities = Array(s.map.numPieces) { Priority.IGNORE }
                    for (p in window) priorities[p] = Priority.TOP_PRIORITY
                    for (p in tail) priorities[p] = Priority.TOP_PRIORITY
                    for (p in waiting) priorities[p] = Priority.TOP_PRIORITY
                    s.handle.prioritizePieces(priorities)
                    s.handle.clearPieceDeadlines()
                    for ((p, ms) in s.map.deadlines(window, have, deadlines)) s.handle.setPieceDeadline(p, ms)
                    for (p in waiting) s.handle.setPieceDeadline(p, 0)
                    lastStart = window.first
                    lastWaiting = waiting
                    lastMissing = missing
                }
            }
            delay(500)
        }
    }

    /** Publishes peers, speeds and how much is ready, once a second. */
    private suspend fun watch(s: TorrentStream) {
        var lastDone = -1L
        while (scope.isActive && !s.closed) {
            runCatching {
                val st = s.handle.status(TorrentHandle.QUERY_PIECES)
                val done = st.totalPayloadDownload()
                if (done != lastDone) {
                    lastDone = done
                    s.lastActivity = monotonicMs()
                }
                val pieces = st.pieces()
                val have = { p: Int -> p < pieces.size() && pieces.getBit(p) }
                val ready = s.map.readableFrom(s.readPosition, have, maxPieces = 4_096)
                val count = s.map.count(have)
                val total = s.map.lastPiece - s.map.firstPiece + 1
                s.mutableStats.value = TorrentStats(
                    phase = when {
                        count == total -> TorrentPhase.COMPLETE
                        ready > 0 -> TorrentPhase.STREAMING
                        else -> TorrentPhase.BUFFERING
                    },
                    peers = st.numPeers(), seeds = st.numSeeds(),
                    downloadBps = st.downloadPayloadRate().toLong(), uploadBps = st.uploadPayloadRate().toLong(),
                    progress = count.toFloat() / total, readyAhead = ready,
                    fileName = s.fileName, fileSize = s.map.fileSize,
                )
            }
            delay(1_000)
        }
    }

    private val pieceAlerts = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.PIECE_FINISHED.swig())
        override fun alert(alert: Alert<*>) {
            val a = alert as? PieceFinishedAlert ?: return
            val key = runCatching { a.handle().infoHash().toHex().lowercase() }.getOrNull() ?: return
            streams[key]?.onPieceFinished(a.pieceIndex())
        }
    }

    /** The libtorrent session, started on first use (leftovers of earlier runs are deleted first). */
    private fun session(): SessionManager {
        session?.let { return it }
        root.deleteRecursively()
        root.mkdirs()
        val settings = SettingsPack()
            .connectionsLimit(200)
            .activeDownloads(4)
            .activeSeeds(4)
            .activeLimit(8)
            .alertQueueSize(2_000)
            .maxQueuedDiskBytes(8 shl 20)
        // Keep connections to seeds while every wanted piece is present: the window moves on.
        settings.setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
        // Connect to more peers per second at the start (default 30).
        settings.setInteger(settings_pack.int_types.connection_speed.swigValue(), 60)
        val manager = SessionManager(false)
        manager.addListener(pieceAlerts)
        // Plain file reads and writes, not memory-mapped files: no address-space limits on 32-bit
        // devices, a full disk is an error instead of a SIGBUS, and libtorrent installs no
        // SIGSEGV/SIGBUS handlers of its own (its mmap storage does).
        manager.start(SessionParams(settings).apply { setPosixDiskIO() })
        session = manager
        return manager
    }

    /** Stops every stream and the session now, and deletes all torrent data. */
    suspend fun shutdown() = lock.withLock {
        idleStop?.cancel()
        streams.values.toList().forEach { closeNow(it) }
        idleStop?.cancel()
        session?.let { runCatching { it.stop() } }
        session = null
        root.deleteRecursively()
    }

    /** Stops the session when nothing has played for a while (DHT and trackers stay quiet). */
    private fun scheduleStop() {
        idleStop?.cancel()
        idleStop = scope.launch {
            delay(IDLE_STOP_MS)
            lock.withLock {
                if (streams.isEmpty()) {
                    session?.let { runCatching { it.stop() } }
                    session = null
                    root.deleteRecursively()
                }
            }
        }
    }

    private companion object {
        const val METADATA_TIMEOUT_MS = 90_000L
        const val NO_DATA_TIMEOUT_MS = 120_000L
        const val IDLE_STOP_MS = 120_000L
        const val MIN_FREE_BYTES = 256L shl 20
        val ARCHIVES = setOf("rar", "zip", "7z", "iso", "exe", "tar", "gz")
    }
}
