package dev.yourstream.log

import android.util.Log
import dev.yourstream.BuildConfig
import dev.yourstream.core.common.Logger
import dev.yourstream.core.common.MemoryTrimmable
import dev.yourstream.core.diagnostics.DiagnosticCategory
import dev.yourstream.core.diagnostics.Redactor
import dev.yourstream.core.diagnostics.Severity
import dev.yourstream.robust.AppDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logcat, an in-memory ring buffer the debug screen shows, and the persistent rotating log file
 * (Phase 13) that survives crashes and restarts. Every line is redacted before it is stored;
 * callers still never pass secrets (see [Logger]). Warnings and errors also go to the
 * diagnostic event journal.
 */
@Singleton
class AppLogger @Inject constructor(private val diagnostics: AppDiagnostics) : Logger, MemoryTrimmable {
    private val maxLines = if (diagnostics.lowRamDevice) 100 else MAX_LINES

    init { dev.yourstream.core.common.MemoryPressure.register(this) }

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, message)
        record("D", tag, message, null)
    }

    override fun i(tag: String, message: String) {
        Log.i(PREFIX + tag, message)
        record("I", tag, message, null)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(PREFIX + tag, message, throwable)
        record("W", tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(PREFIX + tag, message, throwable)
        record("E", tag, message, throwable)
    }

    private fun record(level: String, tag: String, message: String, throwable: Throwable?) {
        val stamp = synchronized(time) { time.format(Date()) }
        val line = Redactor.redact("$stamp $level/$tag: $message" + (throwable?.let { " (${it::class.simpleName}: ${it.message})" } ?: ""))
        _lines.update { (it + line).takeLast(maxLines) }
        if (level != "D" || BuildConfig.DEBUG) diagnostics.log.append(line)
        if (level == "W" || level == "E") {
            diagnostics.events.record(categoryOf(tag), if (level == "E") Severity.ERROR else Severity.WARNING, "$tag: $message", throwable)
        }
    }

    override fun trimMemory(critical: Boolean) = _lines.update { it.takeLast(if (critical) 20 else 50) }

    /** Log tags → diagnostic area. */
    private fun categoryOf(tag: String): DiagnosticCategory {
        val t = tag.lowercase()
        return when {
            t.startsWith("tg") || "telegram" in t || "tdlib" in t || "auth" in t -> DiagnosticCategory.TELEGRAM
            "metadata" in t || "tmdb" in t || "cinemeta" in t -> DiagnosticCategory.METADATA
            "catalog" in t -> DiagnosticCategory.CATALOG
            "database" in t || t == "db" -> DiagnosticCategory.DATABASE
            "play" in t || "stream" in t || "media3" in t -> DiagnosticCategory.PLAYBACK
            "index" in t || "library" in t || "sync" in t -> DiagnosticCategory.INDEXING
            "network" in t || "http" in t -> DiagnosticCategory.NETWORK
            else -> DiagnosticCategory.OTHER
        }
    }

    private companion object {
        const val PREFIX = "YS/"
        const val MAX_LINES = 300
    }
}
