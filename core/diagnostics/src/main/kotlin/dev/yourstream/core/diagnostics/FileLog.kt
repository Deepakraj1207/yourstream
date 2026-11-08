package dev.yourstream.core.diagnostics

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Append-only text log in [dir], rotated at [maxBytes]: `name.log` → `name.1.log` → … up to
 * [maxFiles] files, so it never grows without bound. Writes happen on one background thread
 * (no disk I/O on the caller's thread); [flush] waits for them (crash handler, tests).
 * Every line is redacted. I/O failures are swallowed: logging must never crash the app.
 */
class RotatingFileLog(
    private val dir: File,
    private val name: String = "app",
    private val maxBytes: Long = 512 * 1024,
    private val maxFiles: Int = 3,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ys-log-$name").apply { isDaemon = true } }
    private val current get() = File(dir, "$name.log")
    @Volatile var failures = 0; private set

    fun append(line: String) {
        val text = Redactor.redact(line).replace('\n', ' ') + "\n"
        runCatching { executor.execute { write(text) } }
    }

    /** Writes now on the calling thread (crash handler: the process is about to die). */
    fun appendNow(text: String) {
        flush()
        write(Redactor.redact(text).trimEnd() + "\n")
    }

    private fun write(text: String) {
        try {
            dir.mkdirs()
            val f = current
            if (f.length() + text.length > maxBytes) rotate()
            f.appendText(text)
        } catch (e: Exception) {
            failures++
        }
    }

    private fun rotate() {
        File(dir, "$name.${maxFiles - 1}.log").delete()
        for (i in maxFiles - 2 downTo 1) File(dir, "$name.$i.log").takeIf { it.exists() }?.renameTo(File(dir, "$name.${i + 1}.log"))
        current.renameTo(File(dir, "$name.1.log"))
    }

    /** Waits until queued lines are on disk (bounded). */
    fun flush(timeoutMs: Long = 2_000) {
        runCatching { executor.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Files oldest first. */
    fun files(): List<File> = ((maxFiles - 1 downTo 1).map { File(dir, "$name.$it.log") } + current).filter { it.exists() }

    fun readAll(): String { flush(); return files().joinToString("") { runCatching { it.readText() }.getOrDefault("") } }

    fun tail(lines: Int): List<String> = readAll().lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines)

    fun sizeBytes(): Long = files().sumOf { it.length() }

    fun clear() { flush(); files().forEach { it.delete() } }
}
