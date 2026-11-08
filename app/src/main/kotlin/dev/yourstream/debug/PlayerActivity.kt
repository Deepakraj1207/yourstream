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
) : ViewModel() {
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
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = vm.controller::togglePlayPause) { Text(if (state.playWhenReady) "Pause" else "Play") }
            TextButton(onClick = { vm.controller.seekBy(-10_000) }) { Text("-10s") }
            TextButton(onClick = { vm.controller.seekBy(30_000) }) { Text("+30s") }
            TextButton(onClick = vm::seekFar) { Text("→ 01:20:00") }
            TextButton(onClick = vm::randomSeeks) { Text("5 random seeks") }
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
