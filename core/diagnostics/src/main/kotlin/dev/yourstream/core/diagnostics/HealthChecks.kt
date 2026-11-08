package dev.yourstream.core.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

enum class CheckStatus { OK, WARNING, FAILED, SKIPPED }

data class CheckOutcome(val status: CheckStatus, val summary: String, val details: List<String> = emptyList())

data class CheckResult(
    val name: String,
    val category: DiagnosticCategory,
    val status: CheckStatus,
    val summary: String,
    val details: List<String>,
    val durationMs: Long,
)

/** One self-test of a subsystem. Implementations may throw; the runner turns that into FAILED. */
interface HealthCheck {
    val name: String
    val category: DiagnosticCategory
    suspend fun check(): CheckOutcome
}

fun ok(summary: String, vararg details: String) = CheckOutcome(CheckStatus.OK, summary, details.toList())
fun warning(summary: String, vararg details: String) = CheckOutcome(CheckStatus.WARNING, summary, details.toList())
fun failed(summary: String, vararg details: String) = CheckOutcome(CheckStatus.FAILED, summary, details.toList())

/**
 * Runs every check concurrently, each bounded by [timeoutMs]. A check that hangs or throws is
 * reported, never propagated: diagnostics must work exactly when things are broken.
 */
class DiagnosticsRunner(
    private val checks: Collection<HealthCheck>,
    private val journal: EventJournal? = null,
    private val timeoutMs: Long = 5_000,
) {
    suspend fun run(): List<CheckResult> = coroutineScope {
        checks.map { c -> async { runOne(c) } }.awaitAll().sortedWith(compareBy({ -it.status.ordinal }, { it.category }, { it.name }))
    }

    private suspend fun runOne(c: HealthCheck): CheckResult {
        val start = System.nanoTime()
        val outcome = try {
            withTimeout(timeoutMs) { c.check() }
        } catch (e: TimeoutCancellationException) {
            failed("no answer within ${timeoutMs / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed("check crashed: ${EventJournal.describe(e)}")
        }
        val r = CheckResult(c.name, c.category, outcome.status, Redactor.redact(outcome.summary), outcome.details.map(Redactor::redact), (System.nanoTime() - start) / 1_000_000)
        if (r.status == CheckStatus.FAILED) journal?.warn(c.category, "check \"${c.name}\" failed: ${r.summary}")
        return r
    }
}

/** Plain-text report for sharing (redacted; no library contents, only counts and states). */
object DiagnosticReport {
    fun format(
        info: Map<String, String>,
        checks: List<CheckResult>,
        events: List<DiagnosticEvent>,
        crashes: List<CrashReport>,
        logTail: List<String>,
        formatTime: (Long) -> String = { it.toString() },
    ): String = Redactor.redact(buildString {
        appendLine("# YourStream diagnostics")
        info.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("## Checks")
        checks.forEach { c ->
            appendLine("[${c.status}] ${c.category}/${c.name}: ${c.summary} (${c.durationMs} ms)")
            c.details.forEach { appendLine("    $it") }
        }
        appendLine()
        appendLine("## Crashes (${crashes.size})")
        crashes.forEach { appendLine("${formatTime(it.time)}  ${it.summary}") }
        appendLine()
        appendLine("## Events (newest first)")
        events.forEach { e -> appendLine("${formatTime(e.time)} ${e.severity} ${e.category}: ${e.message}${e.error?.let { " — $it" } ?: ""}") }
        appendLine()
        appendLine("## Log tail")
        logTail.forEach { appendLine(it) }
    })
}
