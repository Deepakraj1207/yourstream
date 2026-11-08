package dev.yourstream.playback.testing

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramFileRepository
import dev.yourstream.telegram.api.model.TelegramFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A Telegram file backend that behaves like TDLib for streaming purposes, with a controllable
 * network:
 *  - files are fetched in aligned parts; one download per file, defined by (offset, limit);
 *    a new download call replaces it (the part in flight is lost, as in TDLib);
 *  - parts already on the device are skipped; prefix sizes are answered for any offset;
 *  - the local copy is a sparse file at the real offsets, exposed as `localPath`;
 *  - bandwidth (shared by all files), per-part latency and outages are simulated in real time.
 */
class SimulatedTelegramFiles(
    private val workDir: File,
    var partSize: Int = 128 * 1024,
) : TelegramFileRepository, AutoCloseable {

    /** Bytes per second for all downloads together; 0 = unlimited. */
    @Volatile var bandwidthBytesPerSecond: Long = 0
    /** Delay before each part (request round trip). */
    @Volatile var latencyMs: Long = 0
    /** While false, downloads make no progress (TDLib waits for the network the same way). */
    @Volatile var online: Boolean = true
    /** When set, download() answers with this error. */
    @Volatile var downloadError: AppError? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = ConcurrentHashMap<Int, SimFile>()
    private val updates = MutableSharedFlow<TelegramFile>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every download call, in order. */
    val downloadCalls = CopyOnWriteArrayList<Pair<Int, Pair<Long, Long>>>()
    val cancelCalls = CopyOnWriteArrayList<Int>()
    val deleteCalls = CopyOnWriteArrayList<Int>()
    val prefixQueries = AtomicLong()
    val partReads = AtomicLong()
    val bytesDownloaded = AtomicLong()

    fun addFile(id: Int, source: File, alreadyDownloaded: Boolean = false): SimFile =
        SimFile(id, source).also { f ->
            files[id] = f
            if (alreadyDownloaded) f.markAll()
        }

    fun file(id: Int): SimFile = files.getValue(id)

    /** Downloads running right now (for concurrency measurements). */
    fun activeDownloads(): Int = files.values.count { it.isActive() }

    /** Makes a file impossible to download (deleted message / expired reference). */
    fun makeUnavailable(id: Int) { file(id).canBeDownloaded = false; emit(file(id)) }

    /** Simulates the message being gone entirely: getFile answers NotFound. */
    fun remove(id: Int) { files.remove(id)?.cancelJob() }

    override val fileUpdates: Flow<TelegramFile> get() = updates

    override suspend fun getFile(fileId: Int): AppResult<TelegramFile> =
        files[fileId]?.let { AppResult.Ok(it.state()) } ?: AppResult.Err(AppError.NotFound("file $fileId"))

    /** Subscribes before reading the current state (like the real repository): no update can fall in between. */
    override fun observeFile(fileId: Int): Flow<TelegramFile> = kotlinx.coroutines.flow.channelFlow {
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { updates.filter { it.id == fileId }.collect { send(it) } }
        files[fileId]?.let { send(it.state()) }
    }

    override suspend fun download(fileId: Int, priority: Int, offset: Long, limit: Long): AppResult<TelegramFile> {
        val f = files[fileId] ?: return AppResult.Err(AppError.NotFound("file $fileId"))
        downloadCalls += fileId to (offset to limit)
        downloadError?.let { return AppResult.Err(it) }
        if (!f.canBeDownloaded) return AppResult.Err(AppError.Telegram(400, "FILE_REFERENCE_EXPIRED"))
        f.start(offset, limit)
        return AppResult.Ok(f.state())
    }

    override suspend fun cancelDownload(fileId: Int): AppResult<Unit> {
        cancelCalls += fileId
        files[fileId]?.cancelJob()
        files[fileId]?.let { emit(it) }
        return AppResult.Ok(Unit)
    }

    override suspend fun downloadedPrefixSize(fileId: Int, offset: Long): AppResult<Long> {
        prefixQueries.incrementAndGet()
        val f = files[fileId] ?: return AppResult.Err(AppError.NotFound("file $fileId"))
        return AppResult.Ok(f.prefix(offset))
    }

    override suspend fun readPart(fileId: Int, offset: Long, count: Long): AppResult<ByteArray> {
        partReads.incrementAndGet()
        val f = files[fileId] ?: return AppResult.Err(AppError.NotFound("file $fileId"))
        val n = minOf(count, f.prefix(offset)).toInt()
        if (n <= 0) return AppResult.Err(AppError.InvalidState("not downloaded"))
        val out = ByteArray(n)
        f.readLocal(offset, out)
        return AppResult.Ok(out)
    }

    override suspend fun deleteLocalCopy(fileId: Int): AppResult<Unit> {
        deleteCalls += fileId
        files[fileId]?.deleteLocal()
        return AppResult.Ok(Unit)
    }

    override fun close() {
        files.values.forEach { it.cancelJob(); it.closeFiles() }
        scope.cancel()
    }

    private fun emit(f: SimFile) { updates.tryEmit(f.state()) }

    inner class SimFile(val id: Int, private val source: File) {
        /** Fixed per file (changing the simulator's part size later affects new files only). */
        val partSize: Int = this@SimulatedTelegramFiles.partSize
        val size: Long = source.length()
        private val parts = ((size + partSize - 1) / partSize).toInt()
        private val done = BitSet(parts)
        @Volatile var canBeDownloaded = true
        private var job: Job? = null
        private var generation = 0
        private var reqOffset = 0L
        private var reqLimit = 0L
        private var active = false
        private var local: File? = null
        private var localRaf: RandomAccessFile? = null
        private val sourceRaf = RandomAccessFile(source, "r")
        /** Parts in the order they were downloaded. */
        val downloadedParts = CopyOnWriteArrayList<Int>()

        @Synchronized fun markAll() {
            ensureLocal()
            for (p in 0 until parts) if (!done[p]) copyPart(p)
        }

        @Synchronized fun state(): TelegramFile {
            val complete = done.cardinality() == parts
            return TelegramFile(
                id = id, remoteUniqueId = "u$id", remoteId = "r$id", size = size, expectedSize = size,
                localPath = local?.absolutePath, canBeDownloaded = canBeDownloaded,
                isDownloadingActive = active && !complete, isDownloadingCompleted = complete,
                downloadOffset = reqOffset, downloadedPrefixSize = prefixLocked(reqOffset),
                downloadedSize = minOf(size, done.cardinality().toLong() * partSize),
            )
        }

        @Synchronized fun prefix(offset: Long): Long = prefixLocked(offset)

        @Synchronized fun isActive(): Boolean = active && done.cardinality() < parts

        private fun prefixLocked(offset: Long): Long {
            if (offset >= size) return 0
            var p = (offset / partSize).toInt()
            if (!done[p]) return 0
            while (p < parts && done[p]) p++
            return minOf(size, p.toLong() * partSize) - offset
        }

        /** Fraction of parts on the device. */
        @Synchronized fun downloadedFraction(): Double = done.cardinality().toDouble() / parts

        @Synchronized fun isDownloaded(position: Long) = done[(position / partSize).toInt()]

        @Synchronized fun readLocal(offset: Long, out: ByteArray) {
            val raf = localRaf ?: error("nothing downloaded")
            raf.seek(offset); raf.readFully(out)
        }

        fun start(offset: Long, limit: Long) {
            synchronized(this) {
                // Same offset while running: only the limit changes, the transfer goes on.
                if (active && job?.isActive == true && offset == reqOffset) { reqLimit = limit; return }
            }
            val previous: Job?
            val gen: Int
            synchronized(this) {
                previous = job
                gen = ++generation
                reqOffset = offset; reqLimit = limit; active = true
                ensureLocal()
            }
            previous?.cancel()
            emit(this)
            job = scope.launch {
                previous?.join()
                val first = (offset / partSize).toInt()
                var p = first
                while (true) {
                    val lim = synchronized(this@SimFile) { reqLimit }
                    val endByte = if (lim == 0L) size else minOf(size, offset + lim)
                    val last = ((endByte - 1) / partSize).toInt()
                    if (p > last) break
                    val current = p++
                    if (!isActive) return@launch
                    if (synchronized(this@SimFile) { done[current] }) continue
                    val bytes = partBytes(current)
                    while (!online && isActive) delay(10)
                    if (latencyMs > 0) delay(latencyMs)
                    val bw = bandwidthBytesPerSecond
                    if (bw > 0) delay(bytes * 1000 / bw)
                    while (!online && isActive) delay(10)
                    if (!isActive) return@launch
                    synchronized(this@SimFile) { copyPart(current) }
                    bytesDownloaded.addAndGet(bytes.toLong())
                    downloadedParts += current
                    emit(this@SimFile)
                }
                synchronized(this@SimFile) { if (generation == gen) active = false }
                emit(this@SimFile)
            }
        }

        private fun partBytes(p: Int) = (minOf(size, (p + 1).toLong() * partSize) - p.toLong() * partSize).toInt()

        private fun copyPart(p: Int) {
            val len = partBytes(p)
            val buf = ByteArray(len)
            synchronized(sourceRaf) { sourceRaf.seek(p.toLong() * partSize); sourceRaf.readFully(buf) }
            val raf = localRaf!!
            raf.seek(p.toLong() * partSize); raf.write(buf)
            done.set(p)
        }

        private fun ensureLocal() {
            if (local == null) {
                workDir.mkdirs()
                local = File(workDir, "tg_file_$id.part").also { it.delete() }
                localRaf = RandomAccessFile(local, "rw")
            }
        }

        fun cancelJob() {
            synchronized(this) { active = false; generation++ }
            job?.cancel()
        }

        @Synchronized fun deleteLocal() {
            cancelJob()
            done.clear()
            downloadedParts.clear()
            localRaf?.close(); localRaf = null
            local?.delete(); local = null
        }

        fun closeFiles() {
            runCatching { localRaf?.close() }
            runCatching { sourceRaf.close() }
        }
    }
}
