package dev.yourstream.debug

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.library.api.WatchHistoryRepository
import dev.yourstream.watch.core.EpisodeProgress
import dev.yourstream.watch.engine.WatchIntelligence
import dev.yourstream.watch.engine.WatchSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Stage-1 debug UI for watch intelligence: the lists, per-item facts, and simulated progress. */
@HiltViewModel
class WatchDebugViewModel @Inject constructor(
    private val watch: WatchIntelligence,
    private val history: WatchHistoryRepository,
    private val media: MediaRepository,
) : ViewModel() {
    private fun <T> live(f: kotlinx.coroutines.flow.Flow<List<T>>) = f.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val continueWatching = live(watch.continueWatching(30))
    val recentlyWatched = live(watch.recentlyWatched(30))
    val recentlyAdded = live(watch.recentlyAdded(30))
    val favorites = live(watch.favorites())
    val abandoned = live(watch.abandoned())
    val detail = MutableStateFlow<Pair<WatchSummary, List<EpisodeProgress>>?>(null)

    fun inspect(itemId: Long) = viewModelScope.launch {
        val s = watch.summary(itemId) ?: return@launch
        detail.value = s to watch.episodes(itemId)
    }
    fun closeDetail() { detail.value = null }

    /** Simulates the player: advances the unit by 25 % of its duration (22 min when unknown). */
    fun simulate(target: PlaybackTarget, currentMs: Long, durationMs: Long, percent: Int = 25) = viewModelScope.launch {
        val d = durationMs.takeIf { it > 0 } ?: (22 * 60_000L)
        history.updateProgress(target, null, (currentMs + d * percent / 100).coerceAtMost(d), d)
        refresh(target.itemId)
    }
    fun markWatched(target: PlaybackTarget, watched: Boolean) = viewModelScope.launch { history.markWatched(target, watched); refresh(target.itemId) }
    fun dismiss(itemId: Long) = viewModelScope.launch { watch.dismiss(itemId); refresh(itemId) }
    fun restore(itemId: Long) = viewModelScope.launch { watch.restore(itemId); refresh(itemId) }
    fun favorite(itemId: Long, on: Boolean) = viewModelScope.launch { media.setFavorite(itemId, on); refresh(itemId) }
    private fun refresh(itemId: Long) { if (detail.value?.first?.itemId == itemId) inspect(itemId) }
}

private val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
private fun ts(t: Long?) = t?.let { time.format(Date(it)) } ?: "never"

@Composable
fun WatchPane(vm: WatchDebugViewModel, openItem: (Long) -> Unit, onPlay: (PlaybackTarget, Long?) -> Unit) {
    var list by rememberSaveable { mutableStateOf(0) }
    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    val recent by vm.recentlyWatched.collectAsStateWithLifecycle()
    val added by vm.recentlyAdded.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val abandoned by vm.abandoned.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()

    detail?.let { (s, eps) ->
        WatchDetail(vm, s, eps, onPlay, openItem)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Continue (${cw.size})", "Recently watched", "Recently added", "Favorites (${favorites.size})", "Abandoned (${abandoned.size})").forEachIndexed { i, n ->
                TextButton(onClick = { list = i }) { Text(if (list == i) "[$n]" else n, fontSize = 12.sp) }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            when (list) {
                0 -> items(cw, key = { "${it.target.itemId}:${it.target.episodeKey}" }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { vm.inspect(e.target.itemId) }.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title + (e.episode?.let { "  ${it.label}" + (it.title?.let { t -> " – $t" } ?: "") } ?: ""), fontSize = 13.sp)
                            Text("${e.action} · ${e.reason} · ${e.percent}%" + (e.seriesPercent?.let { " · series $it%" } ?: "") + " · ${ts(e.activityAt)}", fontSize = 10.sp)
                        }
                        TextButton(onClick = { onPlay(e.target, e.lastMediaId) }) { Text("▶", fontSize = 11.sp) }
                        TextButton(onClick = { vm.simulate(e.target, e.positionMs, e.durationMs) }) { Text("+25%", fontSize = 11.sp) }
                        TextButton(onClick = { vm.dismiss(e.target.itemId) }) { Text("✕", fontSize = 11.sp) }
                    }
                    HorizontalDivider()
                }
                1 -> items(recent, key = { it.summary.itemId }) { r ->
                    SummaryRow(r.summary, extra = (r.lastEpisode?.let { "last ${it.label} ${it.percent}% · " } ?: "") + ts(r.lastWatchedAt)) { vm.inspect(r.summary.itemId) }
                }
                2 -> items(added, key = { it.itemId }) { a ->
                    Column(Modifier.fillMaxWidth().clickable { vm.inspect(a.itemId) }.padding(vertical = 4.dp)) {
                        Text("${a.title}${a.year?.let { " ($it)" } ?: ""}" + (if (a.newEpisodes > 0) "  · ${a.newEpisodes} new episodes" else "") + if (a.favorite) " ★" else "", fontSize = 13.sp)
                        Text("#${a.itemId} ${a.kind} · ${a.status} · added ${ts(a.addedAt)}", fontSize = 10.sp)
                    }
                    HorizontalDivider()
                }
                3 -> items(favorites, key = { it.itemId }) { s -> SummaryRow(s, extra = "") { vm.inspect(s.itemId) } }
                else -> items(abandoned, key = { it.itemId }) { s ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { SummaryRow(s, extra = "${s.abandoned}") { vm.inspect(s.itemId) } }
                        TextButton(onClick = { vm.restore(s.itemId) }) { Text("restore", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(s: WatchSummary, extra: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text("${s.title}${s.year?.let { " ($it)" } ?: ""}${if (s.favorite) " ★" else ""}", fontSize = 13.sp)
        Text(
            "#${s.itemId} ${s.kind} · ${s.status} · ${s.percent}%" +
                (if (s.totalEpisodes > 0) " · ${s.completedEpisodes}/${s.totalEpisodes} eps, ${s.partialEpisodes} partial" else "") +
                (s.next?.episode?.let { " · next ${it.label}" } ?: "") + (if (extra.isNotEmpty()) " · $extra" else ""),
            fontSize = 10.sp,
        )
        HorizontalDivider()
    }
}

@Composable
private fun WatchDetail(vm: WatchDebugViewModel, s: WatchSummary, eps: List<EpisodeProgress>, onPlay: (PlaybackTarget, Long?) -> Unit, openItem: (Long) -> Unit) {
    androidx.activity.compose.BackHandler { vm.closeDetail() }
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = vm::closeDetail) { Text("← back") }
            TextButton(onClick = { openItem(s.itemId) }) { Text("library") }
            TextButton(onClick = { vm.favorite(s.itemId, !s.favorite) }) { Text(if (s.favorite) "★ unfavorite" else "☆ favorite") }
            TextButton(onClick = { if (s.abandoned != null) vm.restore(s.itemId) else vm.dismiss(s.itemId) }) { Text(if (s.abandoned != null) "restore" else "dismiss") }
        }
        Text("${s.title} (${s.kind}) — ${s.status}${s.abandoned?.let { " ($it)" } ?: ""}", fontSize = 14.sp)
        Text(
            "progress ${s.percent}% · position ${s.positionMs / 1000}s / ${s.durationMs / 1000}s · last watched ${ts(s.lastWatchedAt)} · " +
                "watch count ${s.watchCount} · sessions ${s.sessions}" +
                (if (s.totalEpisodes > 0) " · completed ${s.completedEpisodes}, partial ${s.partialEpisodes} of ${s.totalEpisodes}" else ""),
            fontSize = 11.sp,
        )
        s.next?.let { d ->
            Text("next: ${d.action} ${d.episode?.label ?: "-"} (${d.reason})" + (d.anchor?.let { " · based on ${it.label}" } ?: "") +
                (if (d.skippedUnavailable.isNotEmpty()) " · missing ${d.skippedUnavailable.joinToString { it.label }}" else ""), fontSize = 11.sp)
        }
        if (eps.isEmpty()) Row {
            TextButton(onClick = { onPlay(PlaybackTarget(s.itemId), null) }) { Text("▶ play") }
            TextButton(onClick = { vm.simulate(PlaybackTarget(s.itemId), s.positionMs, s.durationMs) }) { Text("+25%") }
            TextButton(onClick = { vm.markWatched(PlaybackTarget(s.itemId), s.status.name != "COMPLETED") }) { Text("toggle watched") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(eps, key = { it.episodeId }) { e ->
                val target = PlaybackTarget(s.itemId, e.episodeId)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${e.label}${e.title?.let { " – $it" } ?: ""}${if (s.next?.episode?.episodeId == e.episodeId) "  ◀ next" else ""}", fontSize = 12.sp)
                        Text("${e.percent}%${if (e.completed) " ✓" else ""} · plays ${e.playCount} · ${ts(e.lastWatchedAt)}${if (!e.available) " · no file" else ""}", fontSize = 10.sp)
                    }
                    TextButton(onClick = { onPlay(target, e.lastMediaId) }) { Text("▶", fontSize = 11.sp) }
                    TextButton(onClick = { vm.simulate(target, e.positionMs, e.durationMs) }) { Text("+25%", fontSize = 11.sp) }
                    TextButton(onClick = { vm.markWatched(target, !e.completed) }) { Text(if (e.completed) "unwatch" else "✓", fontSize = 11.sp) }
                }
                HorizontalDivider()
            }
        }
    }
}
