package dev.yourstream.playback.stream

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.core.common.Logger
import dev.yourstream.core.common.NoOpLogger
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Monotonic time source in nanoseconds (injectable for tests). */
fun interface Ticker { fun nanos(): Long; companion object { val System = Ticker { java.lang.System.nanoTime() } } }

/**
 * Entry point of Telegram streaming. One [FileSession] per file holds the download window shared
 * by all streams of that file; [open] returns a positioned [TelegramFileStream].
 *
 * Only [TelegramFileRepository] is used: no TDLib type reaches this module or its callers.
 */
class TelegramFileStreamer(
    private val repo: TelegramFileRepository,
    scope: CoroutineScope,
    val config: StreamingConfig = StreamingConfig(),
    private val logger: Logger = NoOpLogger,
    private val ticker: Ticker = Ticker.System,
    private val cache: StreamCache? = null,
) {
    private val sessions = ConcurrentHashMap<Int, FileSession>()
    private val observers = java.util.concurrent.CopyOnWriteArrayList<StreamObserver>()

    /**
     * True while the device is known to be offline (set by the network monitor). Waiting for data
     * then does not count towards the stall timeout (up to [StreamingConfig.maxOfflineWaitMs]):
     * the player keeps buffering and continues by itself when the connection returns.
     */
    @Volatile var offlineSignal: () -> Boolean = { false }

    fun addObserver(observer: StreamObserver) { observers += observer }
    fun removeObserver(observer: StreamObserver) { observers -= observer }

    init {
        // Subscribe before any session exists so no progress update is ever missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.fileUpdates.collect { f -> sessions[f.id]?.update(f) }
        }
    }

    /**
     * Opens a stream at [position]. Fails fast with [TelegramStreamException.Unavailable] when the
     * file can be neither read locally nor downloaded.
     */
    suspend fun open(fileId: Int, position: Long, hints: StreamHints = StreamHints()): TelegramFileStream {
        val session = sessions[fileId] ?: run {
            val file = when (val r = repo.getFile(fileId)) {
                is AppResult.Ok -> r.value
                is AppResult.Err -> throw if (r.error is AppError.NotFound) TelegramStreamException.Unavailable(fileId, r.error.message)
                else TelegramStreamException.Telegram(fileId, r.error)
            }
            sessions.computeIfAbsent(fileId) { FileSession(file, config.readAheadFor(hints.copy(sizeBytes = hints.sizeBytes ?: file.size.takeIf { it > 0 }))) }
        }
        session.checkAvailable()
        val size = session.size
        if (size >= 0 && position > size) throw TelegramStreamException.OutOfRange(fileId, position, size)
        session.opened.incrementAndGet()
        session.openStreams.incrementAndGet()
        return TelegramFileStream(session, position)
    }

    /** Current numbers for a file streamed in this process, or null. */
    fun stats(fileId: Int): StreamStats? = sessions[fileId]?.stats()

    /** Files with an open session. */
    val activeFileIds: Set<Int> get() = sessions.keys.toSet()

    /** Changes how far ahead of the reader [fileId] is downloaded (adaptive buffering). */
    fun setReadAhead(fileId: Int, bytes: Long) {
        sessions[fileId]?.let { it.readAhead = bytes.coerceAtLeast(config.minWindowBytes) }
    }

    /**
     * Re-issues the current download of every unfinished session. Used after a network switch or
     * reconnect: a request made on the old network may sit on a dead connection until it times out.
     */
    suspend fun refreshDownloads() {
        for (s in sessions.values) runCatching { s.reissue() }
    }

    /**
     * Playback is focusing on [fileId] (e.g. after a seek): stop downloads of other files nobody is
     * reading right now, so all bandwidth goes to the requested region.
     */
    suspend fun focus(fileId: Int): List<Int> {
        val cancelled = mutableListOf<Int>()
        for (s in sessions.values) {
            if (s.fileId == fileId || s.openStreams.get() > 0 || s.file.isDownloadingCompleted || !s.file.isDownloadingActive) continue
            repo.cancelDownload(s.fileId)
            s.forgetRequest()
            cancelled += s.fileId
        }
        return cancelled
    }

    /**
     * Playback of [fileId] ended (player released / switched media): stop its download and let the
     * cache decide whether to keep the partial copy.
     */
    suspend fun release(fileId: Int, cancelDownload: Boolean = true) {
        val session = sessions.remove(fileId) ?: return
        if (cancelDownload && !session.file.isDownloadingCompleted) repo.cancelDownload(fileId)
        cache?.recordAndTrim(session.file, session.wasCompleteAtStart, activeFileIds = sessions.keys)
    }

    /** One file's shared state: latest Telegram file info, download window, counters. */
    inner class FileSession(initial: TelegramFile, initialReadAhead: Long) {
        /** Upper bound of the download window (set by adaptive buffering). */
        @Volatile var readAhead: Long = initialReadAhead
        /**
         * Current window: starts at [StreamingConfig.initialWindowBytes] after every jump and grows by
         * [StreamingConfig.windowGrowth] on each extension up to [readAhead]. Scrubbing therefore wastes
         * at most a small window per position, while steady playback quickly reaches full read-ahead.
         */
        private fun startWindow() = if (config.initialWindowBytes > 0) minOf(config.initialWindowBytes, readAhead) else readAhead
        @Volatile var window: Long = startWindow()
        private val effectiveWindow get() = minOf(window, readAhead).coerceAtLeast(config.minWindowBytes)
        @Volatile private var lastReader = 0L
        private var markNanos = 0L
        private var markBytes = -1L
        val served = RangeSet()
        val fileId = initial.id
        @Volatile var file: TelegramFile = initial; private set
        val wasCompleteAtStart = initial.isDownloadingCompleted
        private val downloadedAtStart = initial.downloadedSize
        /** Bumped on every update of this file; waiters suspend on it. */
        val version = MutableStateFlow(0L)
        val opened = AtomicInteger()
        val openStreams = AtomicInteger()
        private val windowLock = Mutex()
        private var requestedOffset = -1L
        private var requestedEnd = -1L
        private var lastRequestNanos = 0L
        val requests = java.util.concurrent.CopyOnWriteArrayList<DownloadRequest>()
        val bytesServed = AtomicLong()
        val waits = AtomicLong()
        val waitNanos = AtomicLong()
        val directReads = AtomicLong()
        val partReads = AtomicLong()

        val size: Long get() = file.size.takeIf { it > 0 } ?: file.expectedSize.takeIf { it > 0 } ?: -1L

        fun update(f: TelegramFile) {
            val previous = file
            file = f
            version.value++
            measure(previous, f)
        }

        /** Throughput samples for the network monitor: bytes that arrived since the last mark while downloading. */
        private fun measure(previous: TelegramFile, f: TelegramFile) {
            val now = ticker.nanos()
            synchronized(this) {
                if (offlineSignal() || markBytes < 0) {
                    if (f.isDownloadingActive) { markNanos = now; markBytes = f.downloadedSize }
                    return
                }
                val bytes = f.downloadedSize - markBytes
                if (bytes > 0 && now > markNanos) {
                    val nanos = now - markNanos
                    observers.forEach { it.onTransfer(fileId, bytes, nanos) }
                }
                if (f.isDownloadingActive && !f.isDownloadingCompleted) { markNanos = now; markBytes = f.downloadedSize } else markBytes = -1
            }
            if (previous.downloadedSize > f.downloadedSize) synchronized(this) { markBytes = -1 } // local copy deleted
        }

        private fun markRequest() = synchronized(this) { markNanos = ticker.nanos(); markBytes = file.downloadedSize }

        internal fun forgetRequest() { requestedOffset = -1; requestedEnd = -1; lastRequestNanos = 0 }

        /**
         * Sends the current request again, unchanged: a no-op for a healthy download, a restart for
         * one stuck on a dead connection. (Changing the offset would throw away the part in flight.)
         */
        suspend fun reissue() {
            val offset: Long
            val end: Long
            windowLock.withLock {
                if (file.isDownloadingCompleted || requestedOffset < 0) return
                offset = requestedOffset; end = requestedEnd
                lastRequestNanos = ticker.nanos()
            }
            when (val r = repo.download(fileId, config.priority, offset, (end - offset).coerceAtLeast(1))) {
                is AppResult.Ok -> refreshIfNewer(r.value)
                is AppResult.Err -> Unit // the next read re-requests through maintainWindow
            }
        }

        fun checkAvailable() {
            val f = file
            if (!f.isDownloadingCompleted && !f.canBeDownloaded && f.downloadedSize == 0L) {
                throw TelegramStreamException.Unavailable(fileId, "Telegram reports the file cannot be downloaded")
            }
        }

        private fun refreshIfNewer(f: TelegramFile) {
            // getFile answers may race with update events; keep whichever reports more progress.
            if (f.downloadedSize >= file.downloadedSize || f.isDownloadingCompleted) update(f)
        }

        /** Bytes on the device starting at [position] (0 = not yet). */
        suspend fun prefixAt(position: Long): Long {
            val f = file
            if (f.isDownloadingCompleted) return (size - position).coerceAtLeast(0)
            // Inside the current contiguous window: no Telegram round trip needed.
            if (position >= f.downloadOffset && position < f.downloadOffset + f.downloadedPrefixSize) {
                return f.downloadOffset + f.downloadedPrefixSize - position
            }
            return when (val r = repo.downloadedPrefixSize(fileId, position)) {
                is AppResult.Ok -> r.value
                is AppResult.Err -> throw if (r.error is AppError.NotFound) TelegramStreamException.Unavailable(fileId, r.error.message)
                else TelegramStreamException.Telegram(fileId, r.error)
            }
        }

        /**
         * Keeps Telegram downloading `[first missing byte, reader + readAhead)`. Cheap when nothing
         * needs to change; talks to Telegram only when the download must move or grow:
         *  - Telegram's download head is not where the reader needs data (after a seek, forward or
         *    backward): re-point it, so the bytes in between are never fetched;
         *  - the requested window runs short ahead of the reader: extend it;
         *  - the download stopped before reaching the window end (error/cancel): restart it.
         */
        suspend fun maintainWindow(reader: Long, knownEnd: Long) {
            lastReader = reader
            if (file.isDownloadingCompleted) return
            val size = size
            val win = effectiveWindow
            val wantEnd = if (size >= 0) minOf(size, reader + win) else reader + win
            val f = file
            val frontier = f.downloadOffset + f.downloadedPrefixSize
            // First byte at/after the reader that is not on the device yet.
            val target = maxOf(reader, if (knownEnd in f.downloadOffset..frontier) frontier else knownEnd)
            if (target >= wantEnd) return
            windowLock.withLock {
                val now = ticker.nanos()
                val fresh = now - lastRequestNanos < REQUEST_GRACE_NANOS
                // Telegram's next byte: its reported frontier if it already follows our last request.
                val head = if (requestedOffset >= 0 && f.downloadOffset != requestedOffset && fresh) requestedOffset else frontier
                val running = f.isDownloadingActive || fresh
                val requestedCovers = requestedOffset in 0..target && target < requestedEnd
                val headAtTarget = head <= target + PART_SLACK && target - head <= config.maxGapBytes
                val extendBelow = (win * config.extendAtFraction).toLong()
                val farEnough = requestedEnd >= wantEnd || requestedEnd - reader >= extendBelow
                if (running && requestedCovers && headAtTarget && farEnough) return
                // Window fully fetched and the reader still far from its end: wait (bounds background use).
                if (!f.isDownloadingActive && requestedOffset in 0..target && target >= requestedEnd && requestedEnd - reader >= extendBelow) return
                if (fresh && requestedOffset == target && requestedEnd == wantEnd) return
                // A jump (seek, first request, restart elsewhere) starts a small window; an extension grows it.
                // Continuing the current window (target inside it or just past its end) is an extension;
                // anything else (seek, first request) is a jump.
                val extension = requestedOffset >= 0 && target >= requestedOffset && target <= requestedEnd + config.maxGapBytes
                var end = wantEnd
                if (extension) {
                    window = minOf(readAhead, (window * config.windowGrowth).toLong())
                    end = if (size >= 0) minOf(size, reader + effectiveWindow) else reader + effectiveWindow
                } else if (requestedOffset >= 0 || requests.isNotEmpty()) {
                    window = startWindow().coerceAtMost(readAhead)
                    end = if (size >= 0) minOf(size, reader + effectiveWindow) else reader + effectiveWindow
                }
                if (end <= target) end = if (size >= 0) minOf(size, target + config.minWindowBytes) else target + config.minWindowBytes
                // Extending a running download keeps its offset and only raises the limit: Telegram then
                // continues the transfer instead of restarting the part in flight.
                val offset = if (extension && f.isDownloadingActive && requestedOffset in 0..target) requestedOffset else target
                val limit = (end - offset).coerceAtLeast(1)
                requestedOffset = offset
                requestedEnd = offset + limit
                lastRequestNanos = now
                requests += DownloadRequest(offset, limit, reader)
                if (offset == target) markRequest()
                when (val r = repo.download(fileId, config.priority, offset, limit)) {
                    is AppResult.Ok -> refreshIfNewer(r.value)
                    is AppResult.Err -> {
                        requestedOffset = -1; requestedEnd = -1; lastRequestNanos = 0
                        throw if (r.error is AppError.NotFound) TelegramStreamException.Unavailable(fileId, r.error.message)
                        else TelegramStreamException.Telegram(fileId, r.error)
                    }
                }
            }
        }

        /**
         * Suspends until at least one byte at [position] is downloaded; returns how many are
         * available there. Throws [TelegramStreamException.Stalled] when nothing arrives.
         */
        suspend fun awaitData(position: Long): Long {
            prefixAt(position).let { if (it > 0) return it }
            val start = ticker.nanos()
            var deadline = start + config.stallTimeoutMs * 1_000_000
            val hardDeadline = start + config.stallTimeoutMs * 3 * 1_000_000
            var hard = hardDeadline
            val offlineLimit = start + config.maxOfflineWaitMs * 1_000_000
            var lastProgress = file.downloadedSize
            waits.incrementAndGet()
            try {
                while (true) {
                    checkAvailable()
                    maintainWindow(position, position)
                    val seen = version.value
                    withTimeoutOrNull(config.pollIntervalMs) { version.first { it != seen } }
                    val available = prefixAt(position)
                    if (available > 0) {
                        val waited = ticker.nanos() - start
                        observers.forEach { it.onDataArrived(fileId, waited) }
                        return available
                    }
                    val now = ticker.nanos()
                    val progressed = file.downloadedSize
                    // Progress somewhere in the file (our part on its way) earns more time, within limits.
                    if (progressed > lastProgress) { lastProgress = progressed; deadline = minOf(hard, now + config.stallTimeoutMs * 1_000_000) }
                    // Known offline: waiting is expected, not a stall (bounded).
                    if (offlineSignal() && now < offlineLimit) {
                        deadline = maxOf(deadline, now + config.stallTimeoutMs * 1_000_000)
                        hard = maxOf(hard, deadline)
                    }
                    if (now >= deadline) {
                        observers.forEach { it.onStall(fileId) }
                        throw TelegramStreamException.Stalled(fileId, position, (now - start) / 1_000_000)
                    }
                }
            } finally {
                waitNanos.addAndGet(ticker.nanos() - start)
            }
        }

        fun stats() = StreamStats(
            fileId = fileId,
            size = size,
            readAheadBytes = readAhead,
            windowBytes = effectiveWindow,
            uniqueBytesServed = served.total(),
            downloadedBytes = (file.downloadedSize - downloadedAtStart).coerceAtLeast(0),
            totalDownloadedBytes = file.downloadedSize,
            bytesServed = bytesServed.get(),
            streamsOpened = opened.get(),
            downloadRequests = requests.toList(),
            dataWaits = waits.get(),
            dataWaitMs = waitNanos.get() / 1_000_000,
            directReads = directReads.get(),
            partReads = partReads.get(),
            completed = file.isDownloadingCompleted,
        )

        internal val repository get() = repo
        internal val streamingConfig get() = config
        internal val clock get() = ticker
    }

    private companion object {
        /** A just-issued request counts as running even before Telegram reports it active. */
        const val REQUEST_GRACE_NANOS = 1_000_000_000L
        /** Telegram downloads whole parts; its head may sit up to one part before our byte. */
        const val PART_SLACK = 1024L * 1024L
    }
}

/** One download instruction sent to Telegram: fetch [limit] bytes from [offset] (reader was at [readerPosition]). */
data class DownloadRequest(val offset: Long, val limit: Long, val readerPosition: Long)

data class StreamStats(
    val fileId: Int,
    val size: Long,
    val readAheadBytes: Long,
    /** Current (ramping) download window. */
    val windowBytes: Long = readAheadBytes,
    /** Distinct bytes handed to the player; downloaded − this = bytes never used (so far). */
    val uniqueBytesServed: Long = 0,
    /** Downloaded since streaming started (excludes what was already on the device). */
    val downloadedBytes: Long,
    val totalDownloadedBytes: Long,
    /** Bytes handed to the player. */
    val bytesServed: Long,
    val streamsOpened: Int,
    val downloadRequests: List<DownloadRequest>,
    /** How often the reader had to wait for the network, and for how long in total. */
    val dataWaits: Long,
    val dataWaitMs: Long,
    val directReads: Long,
    val partReads: Long,
    val completed: Boolean,
)

/** Notified by the streamer; the network monitor measures throughput and latency from this. */
interface StreamObserver {
    /** [bytes] arrived in [nanos] while a download was running. */
    fun onTransfer(fileId: Int, bytes: Long, nanos: Long) {}
    /** A reader waited [nanos] for the first byte at a position (request → data). */
    fun onDataArrived(fileId: Int, nanos: Long) {}
    fun onStall(fileId: Int) {}
}

/** Set of byte ranges (merged), for counting distinct bytes. Thread-safe. */
class RangeSet {
    private val ranges = java.util.TreeMap<Long, Long>() // start -> end (exclusive)

    @Synchronized fun add(start: Long, length: Long) {
        if (length <= 0) return
        var s = start
        var e = start + length
        ranges.floorEntry(s)?.let { if (it.value >= s) { s = it.key; e = maxOf(e, it.value) } }
        while (true) {
            val next = ranges.ceilingEntry(s) ?: break
            if (next.key > e) break
            e = maxOf(e, next.value)
            ranges.remove(next.key)
        }
        ranges[s] = e
    }

    @Synchronized fun total(): Long = ranges.entries.sumOf { it.value - it.key }
}
