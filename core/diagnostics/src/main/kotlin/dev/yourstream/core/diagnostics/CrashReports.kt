package dev.yourstream.core.diagnostics

import dev.yourstream.core.common.Clock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

data class CrashReport(val file: File, val time: Long, val summary: String)

/**
 * Crash reports on disk (written synchronously by the uncaught-exception handler, the process
 * is about to die), the newest [keep] kept. Redacted like everything else.
 */
class CrashStore(private val dir: File, private val clock: Clock, private val keep: Int = 10) {

    fun write(thread: String, throwable: Throwable, context: Map<String, String>, recentLog: List<String>): File? = runCatching {
        dir.mkdirs()
        val now = clock.nowMillis()
        val text = buildString {
            appendLine("YourStream crash report")
            appendLine("time: $now")
            appendLine("thread: $thread")
            appendLine("summary: ${EventJournal.describe(throwable)}")
            context.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("--- stack trace ---")
            appendLine(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString())
            appendLine("--- recent log ---")
            recentLog.forEach { appendLine(it) }
        }
        val f = File(dir, "crash-$now.txt")
        f.writeText(Redactor.redact(text))
        prune()
        f
    }.getOrNull()

    fun list(): List<CrashReport> = (dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") } ?: emptyArray())
        .mapNotNull { f ->
            val time = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: return@mapNotNull null
            val summary = runCatching { f.useLines { lines -> lines.firstOrNull { it.startsWith("summary: ") }?.removePrefix("summary: ") } }.getOrNull()
            CrashReport(f, time, summary ?: "?")
        }
        .sortedByDescending { it.time }

    fun read(report: CrashReport): String = runCatching { report.file.readText() }.getOrDefault("")

    fun clear() = list().forEach { it.file.delete() }

    private fun prune() = list().drop(keep).forEach { it.file.delete() }
}

/**
 * Detects crash loops, including native crashes and kills the JVM handler never sees: a marker
 * is written when the app starts and removed once it has run [stableAfterMs]. Finding the marker
 * at the next start means the previous run died early. After [threshold] early deaths in a
 * row (or that many crash reports within [windowMs]), the app starts in **safe mode**: no
 * automatic Telegram start or background work, only the local library and diagnostics.
 */
class StartupGuard(
    private val dir: File,
    private val clock: Clock,
    private val threshold: Int = 3,
    private val windowMs: Long = 10 * 60_000,
) {
    data class Startup(val earlyDeaths: Int, val recentCrashes: Int, val safeMode: Boolean, val forcedSafeMode: Boolean)

    private val marker get() = File(dir, "startup.marker")
    private val forced get() = File(dir, "safe-mode.forced")

    fun begin(crashes: List<CrashReport>): Startup {
        val previousDeaths = runCatching { marker.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() }.getOrNull()
        // The marker holds the count of consecutive early deaths, incremented now.
        val earlyDeaths = previousDeaths?.let { it + 1 } ?: 0
        runCatching { dir.mkdirs(); marker.writeText("$earlyDeaths") }
        val now = clock.nowMillis()
        val recent = crashes.count { now - it.time in 0..windowMs }
        val isForced = forced.exists()
        return Startup(earlyDeaths, recent, isForced || earlyDeaths >= threshold || recent >= threshold, isForced)
    }

    /** The app has run long enough: this start was not a crash loop. */
    fun markStable() { runCatching { marker.delete() } }

    fun forceSafeMode(on: Boolean) { runCatching { if (on) { dir.mkdirs(); forced.writeText("1") } else forced.delete() } }

    val stableAfterMs: Long = 30_000
}
