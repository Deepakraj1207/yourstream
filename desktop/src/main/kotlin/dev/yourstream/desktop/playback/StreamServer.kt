package dev.yourstream.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.yourstream.core.common.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Where the bytes of a file come from: Telegram (streamed while downloading) or a test double. */
interface ByteSource {
    /** Total size, or -1 when unknown. */
    suspend fun size(fileId: Int): Long

    /** Opens a forward reader at [position]. */
    suspend fun open(fileId: Int, position: Long): Reader

    interface Reader : AutoCloseable {
        /** Bytes read (> 0), or -1 at the end. Suspends while the data is downloading. */
        suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    }
}

/**
 * Serves Telegram files to the video player over HTTP on 127.0.0.1, with byte ranges, so the
 * player can seek anywhere while the file is still downloading (what Media3's data source does
 * on Android). Only this computer can connect, and each run uses a fresh random path, so other
 * programs cannot guess the address of a file.
 */
class StreamServer(private val source: ByteSource, private val logger: Logger) : AutoCloseable {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-server").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
        createContext("/$token/") { exchange -> handle(exchange) }
        executor = this@StreamServer.executor
        start()
    }

    private val active = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    val port: Int get() = server.address.port

    /** Ends every read of [fileId] now (playback of it ended), so nothing waits for its data. */
    fun abort(fileId: Int) {
        active.remove(fileId)?.cancel()
    }

    /** The address the player opens for a file. [name] only helps the player guess the format. */
    fun urlFor(fileId: Int, name: String = "video"): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").takeLast(80).ifEmpty { "video" }
        return "http://127.0.0.1:$port/$token/$fileId/$safe"
    }

    private fun handle(exchange: HttpExchange) {
        try {
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            val fileId = exchange.requestURI.path.removePrefix("/$token/").substringBefore('/').toIntOrNull()
            if (fileId == null || exchange.requestMethod !in setOf("GET", "HEAD")) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val size = runBlocking { source.size(fileId) }
            val range = parseRange(exchange.requestHeaders.getFirst("Range"), size)
            if (range == null) {
                exchange.responseHeaders.add("Content-Range", "bytes */$size")
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val (start, endInclusive) = range
            val partial = exchange.requestHeaders.containsKey("Range")
            val length = if (endInclusive >= 0) endInclusive - start + 1 else -1
            exchange.responseHeaders.add("Content-Type", contentType(exchange.requestURI.path))
            if (partial) exchange.responseHeaders.add("Content-Range", "bytes $start-$endInclusive/${if (size >= 0) size else "*"}")
            if (exchange.requestMethod == "HEAD") {
                if (length >= 0) exchange.responseHeaders.add("Content-Length", length.toString())
                exchange.sendResponseHeaders(if (partial) 206 else 200, -1)
                return
            }
            // 0 = chunked (size unknown).
            exchange.sendResponseHeaders(if (partial) 206 else 200, if (length >= 0) length else 0)
            val out = exchange.responseBody
            runBlocking {
                // The newest request for a file wins: a player reads one range at a time, and a
                // seek opens a new connection. The old one could otherwise sit waiting for data
                // at the old position and keep the Telegram download there.
                val job = coroutineContext[Job]!!
                active.put(fileId, job)?.cancel()
                try {
                source.open(fileId, start).use { reader ->
                    val buffer = ByteArray(256 * 1024)
                    var remaining = if (length >= 0) length else Long.MAX_VALUE
                    while (remaining > 0) {
                        val n = reader.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        remaining -= n
                    }
                }
                } finally {
                    active.remove(fileId, job)
                }
            }
            out.flush()
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Replaced by a newer request for the same file, or playback ended.
        } catch (_: IOException) {
            // The player closed the connection (it seeks by opening a new one): normal.
        } catch (e: Exception) {
            logger.w(TAG, "serving a file failed: ${e::class.simpleName}: ${e.message}")
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "StreamServer"

        /**
         * The first byte and the last (inclusive) for a Range header, or null when it cannot be
         * served. No header = the whole file. Only one range is served (players ask for one).
         */
        fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
            val last = if (size >= 0) size - 1 else -1
            if (header == null) return 0L to last
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (!header.trim().startsWith("bytes=") || dash < 0) return 0L to last
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return when {
                a.isEmpty() -> {
                    // "bytes=-500": the last 500 bytes.
                    val n = b.toLongOrNull() ?: return null
                    if (size < 0 || n <= 0) null else maxOf(0, size - n) to last
                }
                else -> {
                    val start = a.toLongOrNull() ?: return null
                    if (size >= 0 && start >= size) return null
                    val end = b.toLongOrNull()?.let { if (last >= 0) minOf(it, last) else it } ?: last
                    if (end in 0 until start) null else start to end
                }
            }
        }

        fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }
}
