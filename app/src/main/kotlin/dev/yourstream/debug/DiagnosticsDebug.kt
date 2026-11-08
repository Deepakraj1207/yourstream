package dev.yourstream.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.BuildConfig
import dev.yourstream.core.common.MemoryPressure
import dev.yourstream.core.database.DatabaseIntegrity
import dev.yourstream.core.database.OpenedDatabase
import dev.yourstream.core.diagnostics.CheckResult
import dev.yourstream.core.diagnostics.CheckStatus
import dev.yourstream.core.diagnostics.CrashReport
import dev.yourstream.core.diagnostics.DiagnosticCategory
import dev.yourstream.core.diagnostics.DiagnosticEvent
import dev.yourstream.core.diagnostics.DiagnosticReport
import dev.yourstream.core.diagnostics.DiagnosticsRunner
import dev.yourstream.core.diagnostics.PlaybackSession
import dev.yourstream.core.diagnostics.Severity
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.robust.AppDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Stage-1 diagnostics screen: self-tests, events, crashes, logs, repair, report export. */
@HiltViewModel
class DiagnosticsDebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: AppDiagnostics,
    private val runner: DiagnosticsRunner,
    private val database: dagger.Lazy<OpenedDatabase>,
) : ViewModel() {
    val checks = MutableStateFlow<List<CheckResult>>(emptyList())
    val running = MutableStateFlow(false)
    val events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val crashes = MutableStateFlow<List<CrashReport>>(emptyList())
    val logTail = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val crashText = MutableStateFlow<String?>(null)
    val minSeverity = MutableStateFlow(Severity.WARNING)
    val interrupted = MutableStateFlow(diagnostics.playbackSession.interrupted(System.currentTimeMillis()))
    val safeMode get() = diagnostics.safeMode
    val info get() = diagnostics.deviceInfo()

    init { refresh(); runChecks() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        events.value = diagnostics.events.recent(200, minSeverity.value)
        crashes.value = diagnostics.crashes.list()
        logTail.value = diagnostics.log.tail(150).reversed()
    }

    fun cycleSeverity() { minSeverity.value = Severity.entries[(minSeverity.value.ordinal + 1) % Severity.entries.size]; refresh() }

    fun runChecks() = viewModelScope.launch {
        running.value = true
        checks.value = runner.run()
        running.value = false
        refresh()
    }

    fun repairDatabase() = viewModelScope.launch {
        message.value = "repairing…"
        message.value = withContext(Dispatchers.IO) {
            runCatching {
                val o = database.get()
                val r = DatabaseIntegrity.repair(o.db.openHelper.writableDatabase, o.file)
                diagnostics.events.warn(DiagnosticCategory.DATABASE, "Manual repair: ${r.actions.joinToString("; ")}")
                "Repair: ${r.actions.joinToString("; ")} → integrity ${if (r.after.ok) "ok" else "still failing: ${r.after.quickCheck}"}"
            }.getOrElse { "Repair failed: ${it.message}" }
        }
        runChecks()
    }

    /** Writes the redacted report to app storage and copies it to the clipboard. */
    fun exportReport() = viewModelScope.launch {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        val text = withContext(Dispatchers.IO) {
            DiagnosticReport.format(diagnostics.deviceInfo(), checks.value, diagnostics.events.recent(300), diagnostics.crashes.list(), diagnostics.log.tail(300)) { time.format(Date(it)) }
        }
        val file = withContext(Dispatchers.IO) {
            runCatching { File(context.getExternalFilesDir(null) ?: context.filesDir, "yourstream-diagnostics-${System.currentTimeMillis()}.txt").apply { writeText(text) } }.getOrNull()
        }
        runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("YourStream diagnostics", text)) }
        message.value = "Report (${text.length / 1024} KB, redacted) copied to the clipboard" + (file?.let { " and saved to ${it.path}" } ?: "")
    }

    fun showCrash(c: CrashReport) { crashText.value = diagnostics.crashes.read(c) }
    fun closeCrash() { crashText.value = null }
    fun clearCrashes() { diagnostics.crashes.clear(); refresh() }
    fun clearLogs() { diagnostics.log.clear(); refresh() }
    fun forceSafeMode(on: Boolean) { diagnostics.guard.forceSafeMode(on); message.value = "Safe mode ${if (on) "on" else "off"} from the next start" }
    fun trimMemory() { message.value = "${MemoryPressure.trim(critical = true)} caches released" }
    fun dismissInterrupted() { diagnostics.playbackSession.clear(); interrupted.value = null }

    /** Debug builds only: proves the crash handler records a report (the app closes). */
    fun testCrash() { if (BuildConfig.DEBUG) throw IllegalStateException("Test crash from the diagnostics screen") }
}

private val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
private fun ts(t: Long) = stamp.format(Date(t))

/** Banner for playback that Android interrupted by killing the process. */
@Composable
fun InterruptedPlaybackBanner(vm: DiagnosticsDebugViewModel, onPlay: (PlaybackTarget, Long?) -> Unit) {
    val s by vm.interrupted.collectAsStateWithLifecycle()
    val session: PlaybackSession = s ?: return
    Row {
        Text("Interrupted: ${session.title.ifEmpty { "#${session.itemId}" }} at ${session.positionMs / 60_000} min", fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
        TextButton(onClick = { onPlay(PlaybackTarget(session.itemId, session.episodeId), session.mediaId); vm.interrupted.value = null }) { Text("Resume") }
        TextButton(onClick = vm::dismissInterrupted) { Text("✕") }
    }
}

@Composable
fun DiagnosticsPane(vm: DiagnosticsDebugViewModel) {
    var section by rememberSaveable { mutableStateOf(0) }
    val checks by vm.checks.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val crashes by vm.crashes.collectAsStateWithLifecycle()
    val log by vm.logTail.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val crashText by vm.crashText.collectAsStateWithLifecycle()
    val severity by vm.minSeverity.collectAsStateWithLifecycle()

    crashText?.let { text ->
        androidx.activity.compose.BackHandler { vm.closeCrash() }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = vm::closeCrash) { Text("← back") }
            LazyColumn { items(text.lines()) { Text(it, fontSize = 9.sp) } }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (vm.safeMode) Text("SAFE MODE: repeated crashes at startup; Telegram and background work were not started.", color = Color(0xFFB00020), fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.runChecks() }, enabled = !running) { Text(if (running) "checking…" else "Run checks") }
            TextButton(onClick = { vm.repairDatabase() }) { Text("Repair database") }
            TextButton(onClick = { vm.exportReport() }) { Text("Export report") }
            TextButton(onClick = vm::trimMemory) { Text("Trim memory") }
            TextButton(onClick = { vm.forceSafeMode(!vm.safeMode) }) { Text(if (vm.safeMode) "Leave safe mode" else "Force safe mode") }
            TextButton(onClick = vm::clearLogs) { Text("Clear logs") }
            if (BuildConfig.DEBUG) TextButton(onClick = vm::testCrash) { Text("Test crash", color = Color(0xFFB00020)) }
        }
        message?.let { Text(it, fontSize = 11.sp) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Checks", "Events", "Crashes (${crashes.size})", "Log", "Device").forEachIndexed { i, n ->
                TextButton(onClick = { section = i; vm.refresh() }) { Text(if (section == i) "[$n]" else n, fontSize = 12.sp) }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            when (section) {
                0 -> items(checks, key = { it.name }) { c ->
                    var open by rememberSaveable(c.name) { mutableStateOf(c.status != CheckStatus.OK) }
                    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 3.dp)) {
                        Text("${mark(c.status)} ${c.category}/${c.name}: ${c.summary}", fontSize = 12.sp, color = color(c.status))
                        if (open) c.details.forEach { Text("   $it", fontSize = 10.sp) }
                        Text("   ${c.durationMs} ms", fontSize = 9.sp)
                    }
                    HorizontalDivider()
                }
                1 -> {
                    item { TextButton(onClick = vm::cycleSeverity) { Text("showing ≥ $severity", fontSize = 11.sp) } }
                    items(events) { e ->
                        Text("${ts(e.time)} ${e.severity} ${e.category}: ${e.message}${e.error?.let { " — $it" } ?: ""}", fontSize = 10.sp,
                            color = if (e.severity == Severity.ERROR) Color(0xFFB00020) else Color.Unspecified)
                        HorizontalDivider()
                    }
                }
                2 -> {
                    if (crashes.isNotEmpty()) item { TextButton(onClick = vm::clearCrashes) { Text("Clear crash reports") } }
                    items(crashes, key = { it.time }) { c ->
                        Text("${ts(c.time)}  ${c.summary}", fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { vm.showCrash(c) }.padding(vertical = 4.dp))
                        HorizontalDivider()
                    }
                }
                3 -> items(log) { Text(it, fontSize = 9.sp) }
                else -> items(vm.info.entries.toList()) { (k, v) -> Text("$k: $v", fontSize = 11.sp) }
            }
        }
    }
}

private fun mark(s: CheckStatus) = when (s) { CheckStatus.OK -> "✓"; CheckStatus.WARNING -> "!"; CheckStatus.FAILED -> "✗"; CheckStatus.SKIPPED -> "–" }
private fun color(s: CheckStatus) = when (s) { CheckStatus.FAILED -> Color(0xFFB00020); CheckStatus.WARNING -> Color(0xFF8A6D00); else -> Color.Unspecified }
