package dev.yourstream.core.diagnostics

import dev.yourstream.core.common.Clock
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

enum class Severity { INFO, WARNING, ERROR }

enum class DiagnosticCategory { STARTUP, TELEGRAM, NETWORK, METADATA, CATALOG, DATABASE, PLAYBACK, INDEXING, WORK, STORAGE, MEMORY, CRASH, OTHER }

data class DiagnosticEvent(
    val time: Long,
    val severity: Severity,
    val category: DiagnosticCategory,
    val message: String,
    /** "IOException: …" — class and message only, never a full payload. */
    val error: String? = null,
) {
    fun line() = "$time\t$severity\t$category\t${message.oneLine()}\t${error?.oneLine().orEmpty()}"

    companion object {
        fun parse(line: String): DiagnosticEvent? {
            val p = line.split('\t')
            if (p.size < 4) return null
            return runCatching {
                DiagnosticEvent(p[0].toLong(), Severity.valueOf(p[1]), DiagnosticCategory.valueOf(p[2]), p[3], p.getOrNull(4)?.ifEmpty { null })
            }.getOrNull()
        }

        private fun String.oneLine() = replace('\t', ' ').replace('\n', ' ')
    }
}

/**
 * Structured record of what went wrong (and notable recoveries), per category. Kept in memory
 * for the debug screen and persisted (rotating file), so failures before a crash or restart can
 * still be read afterwards.
 */
class EventJournal(
    private val clock: Clock,
    private val file: RotatingFileLog? = null,
    private val capacity: Int = 500,
) {
    private val events = ConcurrentLinkedDeque<DiagnosticEvent>()
    /** ConcurrentLinkedDeque.size is O(n); track it. */
    private val size = AtomicInteger()
    private val counts = DiagnosticCategory.entries.associateWith { AtomicInteger() }
    private val errors = AtomicInteger()

    init {
        // Events from previous runs (marked by time; counts are for this run only).
        file?.tail(capacity)?.mapNotNull(DiagnosticEvent::parse)?.forEach { events.addLast(it); size.incrementAndGet() }
    }

    fun record(category: DiagnosticCategory, severity: Severity, message: String, error: Throwable? = null): DiagnosticEvent {
        val e = DiagnosticEvent(clock.nowMillis(), severity, category, Redactor.redact(message), error?.let { Redactor.redact(describe(it)) })
        events.addLast(e)
        if (size.incrementAndGet() > capacity && events.pollFirst() != null) size.decrementAndGet()
        if (severity != Severity.INFO) counts.getValue(category).incrementAndGet()
        if (severity == Severity.ERROR) errors.incrementAndGet()
        file?.append(e.line())
        return e
    }

    fun info(category: DiagnosticCategory, message: String) = record(category, Severity.INFO, message)
    fun warn(category: DiagnosticCategory, message: String, error: Throwable? = null) = record(category, Severity.WARNING, message, error)
    fun error(category: DiagnosticCategory, message: String, error: Throwable? = null) = record(category, Severity.ERROR, message, error)

    /** Newest first. */
    fun recent(limit: Int = 100, minSeverity: Severity = Severity.INFO, category: DiagnosticCategory? = null): List<DiagnosticEvent> =
        events.reversed().asSequence().filter { it.severity >= minSeverity && (category == null || it.category == category) }.take(limit).toList()

    /** Warnings + errors per category in this run. */
    fun problemCounts(): Map<DiagnosticCategory, Int> = counts.mapValues { it.value.get() }.filterValues { it > 0 }

    fun errorCount() = errors.get()

    /** Forgets every event, in memory and on disk (signing out: events can name chats and titles). */
    fun clear() {
        events.clear()
        size.set(0)
        counts.values.forEach { it.set(0) }
        errors.set(0)
        file?.clear()
    }

    companion object {
        fun describe(t: Throwable): String {
            val root = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.last()
            val head = "${t::class.java.simpleName}: ${t.message.orEmpty()}".take(300)
            return if (root === t) head else "$head (cause ${root::class.java.simpleName}: ${root.message.orEmpty().take(200)})"
        }
    }
}
