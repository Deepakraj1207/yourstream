package dev.yourstream.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.playback.media3.PlaybackMetrics
import dev.yourstream.playback.media3.PlaybackState
import dev.yourstream.playback.media3.TelegramPlaybackController
import dev.yourstream.playback.media3.TelegramPlaybackControllerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

/**
 * Stage-1 minimal test player: Media3's stock PlayerView plus state, metrics and test buttons
 * (far seek, random seeks, retry, fast-seek toggle). Replaced by the real player UI in Stage 2.
 */
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    private val vm: PlayerDebugViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val target = PlaybackTarget(intent.getLongExtra(EXTRA_ITEM, -1), intent.getLongExtra(EXTRA_EPISODE, -1).takeIf { it >= 0 })
        vm.start(target, intent.getLongExtra(EXTRA_MEDIA, -1).takeIf { it >= 0 })
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { PlayerScreen(vm) } } }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.controller.pause()
    }

    companion object {
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_EPISODE = "episode"
        private const val EXTRA_MEDIA = "media"

        fun intent(context: Context, target: PlaybackTarget, mediaId: Long?) = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_ITEM, target.itemId)
            .putExtra(EXTRA_EPISODE, target.episodeId ?: -1L)
            .putExtra(EXTRA_MEDIA, mediaId ?: -1L)
    }
}

@HiltViewModel
class PlayerDebugViewModel @Inject constructor(
    factory: TelegramPlaybackControllerFactory,
    private val history: WatchHistoryRepository,
    private val trackPrefs: dev.yourstream.playback.tracks.TrackPreferenceStore,
) : ViewModel() {
    val languagePrefs = MutableStateFlow(dev.yourstream.playback.tracks.LanguagePreferences())

    init { viewModelScope.launch { languagePrefs.value = trackPrefs.preferences() } }

    /** Saves "ta,en,hi" style lists; applies from the next file played. */
    fun saveLanguages(audio: String, subtitles: String, fallback: String, mode: dev.yourstream.playback.tracks.SubtitleMode) = viewModelScope.launch {
        val p = dev.yourstream.playback.tracks.LanguagePreferences(
            audio = audio.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            subtitles = subtitles.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            fallbackLanguage = fallback.trim().ifEmpty { null },
            subtitleMode = mode,
        )
        trackPrefs.setPreferences(p)
        languagePrefs.value = trackPrefs.preferences()
    }

    /** Lives in the ViewModel so rotation does not restart playback. */
    val controller: TelegramPlaybackController = factory.create()
    val message = MutableStateFlow<String?>(null)
    private var started = false
    var fastSeek = false; private set

    fun start(target: PlaybackTarget, mediaId: Long?) {
        if (started) return
        started = true
        viewModelScope.launch {
            // Continue where the user stopped (unless finished).
            val resume = history.progress(target).first()?.takeIf { !it.completed }?.positionMs
            controller.open(target, mediaId, resume)
        }
    }

    fun seekFar() {
        val duration = controller.state.value.durationMs ?: return
        controller.seekTo(minOf(80 * 60_000L, duration - 5_000).coerceAtLeast(0)) // 01:20:00, or near the end
    }

    fun randomSeeks() {
        val duration = controller.state.value.durationMs ?: return
        repeat(5) { controller.seekTo(Random.nextLong(0, duration.coerceAtLeast(1))) }
    }

    /** Simulated seek-bar drag: 20 positions over ~1 s, then release (shows coalescing in the metrics). */
    fun scrubTest() {
        val duration = controller.state.value.durationMs ?: return
        val from = controller.state.value.positionMs
        viewModelScope.launch {
            controller.beginScrub()
            var pos = from
            repeat(20) { pos = (pos + duration / 100).coerceAtMost(duration - 1); controller.seekTo(pos); kotlinx.coroutines.delay(50) }
            controller.endScrub(pos)
        }
    }

    fun toggleFastSeek() { fastSeek = !fastSeek; controller.setFastSeeking(fastSeek) }

    override fun onCleared() = controller.release()
}

@Composable
private fun PlayerScreen(vm: PlayerDebugViewModel) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val metrics by vm.controller.metrics.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp).verticalScroll(rememberScrollState())) {
        AndroidView(
            factory = { PlayerView(it).apply { player = vm.controller.player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
        )
        Text(state.line(), fontSize = 12.sp)
        state.error?.let { Text("ERROR ${it.kind}: ${it.message}${if (it.retryable) " (retryable)" else ""}", color = Color(0xFFB00020), fontSize = 12.sp) }
        metrics.lines().forEach { Text(it, fontSize = 11.sp) }
        vm.controller.adaptive?.let { a -> a.lines(state).forEach { Text(it, fontSize = 10.sp) } } ?: Text("adaptive streaming off", fontSize = 10.sp)
        TrackSection(vm)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = vm.controller::togglePlayPause) { Text(if (state.playWhenReady) "Pause" else "Play") }
            TextButton(onClick = { vm.controller.seekBy(-10_000) }) { Text("-10s") }
            TextButton(onClick = { vm.controller.seekBy(30_000) }) { Text("+30s") }
            TextButton(onClick = vm::seekFar) { Text("→ 01:20:00") }
            TextButton(onClick = vm::randomSeeks) { Text("5 random seeks") }
            TextButton(onClick = vm::scrubTest) { Text("scrub test") }
            TextButton(onClick = vm.controller::retry) { Text("Retry") }
            TextButton(onClick = vm::toggleFastSeek) { Text(if (vm.fastSeek) "[fast seek]" else "fast seek") }
            TextButton(onClick = vm.controller::stop) { Text("Stop") }
        }
    }
}

private fun time(ms: Long?): String {
    if (ms == null) return "--:--"
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

private fun PlaybackState.line() = buildString {
    append("${status}${if (isPlaying) " ▶" else ""} ${time(positionMs)} / ${time(durationMs)} · buffered ${time(bufferedPositionMs)}")
    if (videoWidth != null) append(" · ${videoWidth}x$videoHeight")
    media?.let { append(" · ${it.fileName} (${it.sizeBytes / (1 shl 20)} MB${if (it.alreadyDownloaded) ", on device" else ""})") }
}

private fun PlaybackMetrics.lines() = listOf(
    "startup ${startupMs ?: "–"} ms · first frame ${firstFrameMs ?: "–"} ms · rebuffers $rebufferCount (${rebufferMs} ms) · retries $retries",
    "seeks $seekCount · last ${lastSeekLatencyMs ?: "–"} ms · median ${seekLatenciesMs.sorted().getOrNull(seekLatenciesMs.size / 2) ?: "–"} ms",
    "served ${bytesServed / 1024} KB · downloaded ${downloadedBytes / 1024} KB in $downloadRequests requests · waited for data $dataWaitMs ms",
    "memory peak: java ${peakJavaHeapBytes / (1 shl 20)} MB · native ${peakNativeHeapBytes / (1 shl 20)} MB",
)

/** Diagnostics of the adaptive engine (Stage 1 only). */
private fun dev.yourstream.playback.media3.AdaptivePlayback.lines(@Suppress("UNUSED_PARAMETER") tick: PlaybackState): List<String> {
    val p = streaming.policy
    val n = streaming.network.state.value
    val g = streaming.governor.stats.value
    val pf = prefetch?.state
    return listOf(
        "network: ${n.connectivity.type}${if (n.isMetered) " (metered)" else ""} · ${n.quality} · ${n.bytesPerSecond?.let { "${it * 8 / 1000} kbit/s" } ?: "measuring"}" +
            (n.latencyMs?.let { " · ${it} ms" } ?: "") + (if (n.recentStalls > 0) " · ${n.recentStalls} stalls" else ""),
        "buffer: start ${p.startPlaybackMs / 1000.0}s · resume ${p.resumeAfterRebufferMs / 1000.0}s · RAM ${p.minBufferMs / 1000}-${p.maxBufferMs / 1000}s (≤${p.targetBufferBytes / (1 shl 20)} MB) · disk ${p.readAheadBytes / (1 shl 20)} MB · risk ${p.risk}" +
            (streaming.buffer.timeToEmptyMs?.let { " · empty in ${it / 1000}s" } ?: ""),
        "why: ${p.reason}",
        "downloads: ${g.active.size} active ${g.active.values.groupingBy { it }.eachCount()} · peak ${g.peakConcurrent} · preempted ${g.preempted} · refused ${g.rejected}" +
            " · prefetch ${if (g.prefetchPaused) "paused" else "allowed=${p.prefetchAllowed}"}",
        "next episode: ${pf?.status ?: "–"}${pf?.candidate?.let { " ${it.label}" } ?: ""}${pf?.let { if (it.prefetchedBytes > 0) " ${it.prefetchedBytes / 1024} KB" else "" } ?: ""}${pf?.reason?.let { " ($it)" } ?: ""}",
    )
}

@Composable
private fun TrackSection(vm: PlayerDebugViewModel) {
    val tracks by vm.controller.tracks.collectAsStateWithLifecycle()
    val prefs by vm.languagePrefs.collectAsStateWithLifecycle()
    val sel = tracks.selection
    Text("Audio: ${sel?.audio?.rule ?: "–"} · ${sel?.audio?.detail ?: ""}", fontSize = 11.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        tracks.audio.forEach { t ->
            val chosen = sel?.audio?.track?.id == t.id
            TextButton(onClick = { vm.controller.selectAudio(t.id) }) { Text((if (chosen) "[" else "") + t.describe() + (if (chosen) "]" else ""), fontSize = 10.sp) }
        }
    }
    Text("Subtitles: ${sel?.subtitle?.rule ?: "–"} · ${sel?.subtitle?.detail ?: ""}", fontSize = 11.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { vm.controller.selectSubtitle(null) }) { Text(if (sel?.subtitle?.track == null) "[off]" else "off", fontSize = 10.sp) }
        tracks.subtitles.forEach { t ->
            val chosen = sel?.subtitle?.track?.id == t.id
            TextButton(onClick = { vm.controller.selectSubtitle(t.id) }) { Text((if (chosen) "[" else "") + t.describe() + (if (chosen) "]" else ""), fontSize = 10.sp) }
        }
    }
    var audio by androidx.compose.runtime.remember(prefs) { androidx.compose.runtime.mutableStateOf(prefs.audio.joinToString(",")) }
    var subs by androidx.compose.runtime.remember(prefs) { androidx.compose.runtime.mutableStateOf(prefs.subtitles.joinToString(",")) }
    var fallback by androidx.compose.runtime.remember(prefs) { androidx.compose.runtime.mutableStateOf(prefs.fallbackLanguage.orEmpty()) }
    var mode by androidx.compose.runtime.remember(prefs) { androidx.compose.runtime.mutableStateOf(prefs.subtitleMode) }
    Text("Language preferences (codes or names, in order; apply from the next file)", fontSize = 11.sp)
    Row {
        androidx.compose.material3.OutlinedTextField(audio, { audio = it }, label = { Text("audio") }, singleLine = true, modifier = Modifier.weight(1f))
        androidx.compose.material3.OutlinedTextField(subs, { subs = it }, label = { Text("subtitles") }, singleLine = true, modifier = Modifier.weight(1f))
        androidx.compose.material3.OutlinedTextField(fallback, { fallback = it }, label = { Text("fallback") }, singleLine = true, modifier = Modifier.weight(0.6f))
    }
    Row {
        TextButton(onClick = { mode = dev.yourstream.playback.tracks.SubtitleMode.entries[(mode.ordinal + 1) % 3] }) { Text("subtitles: $mode", fontSize = 11.sp) }
        TextButton(onClick = { vm.saveLanguages(audio, subs, fallback, mode) }) { Text("Save", fontSize = 11.sp) }
    }
}

private fun dev.yourstream.playback.tracks.AnalyzedTrack.describe(): String = buildString {
    append(dev.yourstream.playback.tracks.LanguageCatalog.displayName(language.code))
    track.label?.let { append(" \"$it\"") }
    append(" (${language.source.name.lowercase()} ${"%.1f".format(Locale.US, language.confidence)})")
    if (track.origin != dev.yourstream.playback.tracks.TrackOrigin.EMBEDDED) append(" ${track.origin.name.lowercase()}")
    if (track.isForced) append(" forced")
    if (track.isHearingImpaired) append(" SDH")
    if (track.isCommentary) append(" commentary")
    track.channels?.let { append(" ${it}ch") }
    if (!track.supported) append(" UNSUPPORTED")
}
