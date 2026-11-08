package dev.yourstream.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.yourstream.library.api.LibraryItemDetails
import dev.yourstream.library.api.LibraryItemSummary
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.PlaybackTarget
import java.util.Locale

// Stage-1 debug UI for the library. Plain on purpose.

@Composable
fun LibraryPane(vm: LibraryDebugViewModel, onPlay: (PlaybackTarget, Long?) -> Unit) {
    val details by vm.details.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    message?.let { Text(it, fontSize = 12.sp, modifier = Modifier.clickable { vm.dismissMessage() }) }
    val d = details
    if (d != null) {
        BackHandler { vm.close() }
        ItemPane(vm, d, onPlay)
    } else {
        ListPane(vm)
    }
}

@Composable
private fun ListPane(vm: LibraryDebugViewModel) {
    val query by vm.query.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    val items = vm.results()
    var text by remember { mutableStateOf(query.text) }
    Column(Modifier.fillMaxSize()) {
        if (cw.isNotEmpty()) {
            Text("Continue watching", fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                cw.forEach { e ->
                    TextButton(onClick = { vm.open(e.target.itemId) }) {
                        Text("${e.title}${e.episodeNumber?.let { " E$it" } ?: ""} ${(e.progress.fraction * 100).toInt()}%", fontSize = 11.sp)
                    }
                }
            }
        }
        OutlinedTextField(value = text, onValueChange = { text = it; vm.setText(it) }, label = { Text("Search library") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            val kind = query.kinds.firstOrNull()
            (listOf<LibraryKind?>(null) + LibraryKind.entries).forEach { k ->
                TextButton(onClick = { vm.setKind(k) }) { Text(if (k == kind) "[${k?.name ?: "ALL"}]" else k?.name ?: "ALL", fontSize = 12.sp) }
            }
            TextButton(onClick = vm::cycleSort) { Text("sort: ${query.sort}${if (query.reversed) "↑" else ""}", fontSize = 12.sp) }
            TextButton(onClick = vm::toggleReversed) { Text("reverse", fontSize = 12.sp) }
            TextButton(onClick = vm::cycleWatch) { Text("watch: ${query.watch}", fontSize = 12.sp) }
            TextButton(onClick = vm::toggleFavoritesOnly) { Text(if (query.favoritesOnly) "[favorites]" else "favorites", fontSize = 12.sp) }
            TextButton(onClick = vm::toggleUnavailable) { Text(if (query.includeUnavailable) "[unavailable]" else "unavailable", fontSize = 12.sp) }
            TextButton(onClick = { vm.setLanguage(if (query.language == "ta") null else "ta") }) { Text(if (query.language == "ta") "[ta]" else "ta", fontSize = 12.sp) }
            TextButton(onClick = vm::syncNow) { Text("sync now", fontSize = 12.sp) }
            TextButton(onClick = vm::runMetadata) { Text("match metadata", fontSize = 12.sp) }
        }
        val counts by vm.matchCounts.collectAsStateWithLifecycle()
        Text("$count items · metadata: ${counts.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none yet" }}", fontSize = 12.sp)
        Text(vm.providersLine(), fontSize = 10.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { i -> items[i]?.let { ItemRow(it) { vm.open(it.id) } } }
        }
    }
}

@Composable
private fun LibraryDebugViewModel.results() = items.collectAsLazyPagingItems()

@Composable
private fun ItemRow(item: LibraryItemSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp)) {
        Text((if (item.isFavorite) "★ " else "") + item.title + (item.year?.let { " ($it)" } ?: "") +
            (item.metadataTitle?.takeIf { it != item.title }?.let { "  → $it" } ?: "") + (if (item.posterUrl != null) "  🖼" else ""), fontSize = 13.sp)
        Text(
            listOfNotNull(
                "#${item.id}", item.kind.name,
                if (item.kind == LibraryKind.SERIES) "${item.seasonCount} seasons, ${item.episodeCount} eps, ${item.unwatchedEpisodeCount} unwatched" else "${item.availableCount} file(s)",
                item.maxResolution?.let { "${it}p" },
                item.progress?.let { if (it.completed) "watched" else "${(it.fraction * 100).toInt()}%" },
                if (!item.isAvailable) "UNAVAILABLE" else null,
            ).joinToString(" · "),
            fontSize = 11.sp,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ItemPane(vm: LibraryDebugViewModel, d: LibraryItemDetails, onPlay: (PlaybackTarget, Long?) -> Unit) {
    val s = d.summary
    val seasonId by vm.seasonId.collectAsStateWithLifecycle()
    val episodes = vm.episodes.collectAsLazyPagingItems()
    var mergeTarget by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = vm::close) { Text("< Back") }
        Text("#${s.id} ${s.kind}: ${s.title}${s.year?.let { " ($it)" } ?: ""}${if (d.locked) " [locked]" else ""}${if (d.hidden) " [hidden]" else ""}")
        Text("key: ${d.normalizedTitle} · languages: ${d.languages.joinToString().ifEmpty { "—" }} · files: ${s.availableCount} · ${size(s.totalSize)}", fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (s.kind != LibraryKind.SERIES) TextButton(onClick = { onPlay(PlaybackTarget(s.id), null) }) { Text("▶ Play") }
            TextButton(onClick = { vm.favorite(s.id, !s.isFavorite) }) { Text(if (s.isFavorite) "Unfavorite" else "Favorite") }
            if (s.kind != LibraryKind.SERIES) {
                TextButton(onClick = { vm.simulatePlayback(PlaybackTarget(s.id), s.progress?.positionMs) }) { Text("Play +20%") }
            }
            TextButton(onClick = { vm.markWatched(PlaybackTarget(s.id), true) }) { Text("Mark watched") }
            TextButton(onClick = { vm.markWatched(PlaybackTarget(s.id), false) }) { Text("Unwatch") }
        }
        s.progress?.let { Text("progress: ${it.positionMs / 1000}s / ${it.durationMs / 1000}s${if (it.completed) " (completed)" else ""}", fontSize = 11.sp) }
        Row {
            OutlinedTextField(value = mergeTarget, onValueChange = { mergeTarget = it.filter(Char::isDigit) }, label = { Text("merge into #") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            TextButton(onClick = { mergeTarget.toLongOrNull()?.let(vm::mergeInto) }) { Text("Merge") }
        }
        MetadataSection(vm, s.id)
        if (d.versions.isNotEmpty()) {
            Text("Versions", fontSize = 12.sp)
            Column(Modifier.heightIn(max = 160.dp)) {
                d.versions.forEach { v ->
                    Row {
                        Text("• ${v.fileName} · ${v.resolution?.let { "${it}p" } ?: "?"} · ${size(v.sizeBytes)}" +
                            (if (v.isDuplicate) " · dup" else "") + (if (v.isDeleted) " · DELETED" else "") + (if (v.isManual) " · manual" else ""),
                            fontSize = 11.sp, modifier = Modifier.weight(1f).padding(top = 10.dp))
                        TextButton(onClick = { onPlay(PlaybackTarget(s.id, v.episodeId), v.mediaId) }) { Text("▶", fontSize = 11.sp) }
                    }
                }
            }
        }
        if (s.kind == LibraryKind.SERIES) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { vm.selectSeason(null) }) { Text(if (seasonId == null) "[All]" else "All", fontSize = 12.sp) }
                d.seasons.forEach { season ->
                    TextButton(onClick = { vm.selectSeason(season.id) }) {
                        Text((if (seasonId == season.id) "[" else "") + "${season.displayName} (${season.episodeCount})" + (if (seasonId == season.id) "]" else ""), fontSize = 12.sp)
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(count = episodes.itemCount, key = episodes.itemKey { it.id }) { i ->
                    episodes[i]?.let { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${e.label}${e.absoluteNumber?.let { " (#$it)" } ?: ""}  ${e.availableCount} file(s)  " +
                                    (e.progress?.let { if (it.completed) "✓" else "${(it.fraction * 100).toInt()}%" } ?: ""),
                                fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 12.dp),
                            )
                            TextButton(onClick = { onPlay(e.target, null) }) { Text("▶", fontSize = 11.sp) }
                            TextButton(onClick = { vm.simulatePlayback(e.target, e.progress?.positionMs) }) { Text("+20%", fontSize = 11.sp) }
                            TextButton(onClick = { vm.markWatched(e.target, e.progress?.completed != true) }) { Text("✓", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

private fun size(n: Long): String = when {
    n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / (1L shl 30).toDouble())
    else -> String.format(Locale.US, "%.0f MB", n / (1L shl 20).toDouble())
}

@Composable
private fun MetadataSection(vm: LibraryDebugViewModel, itemId: Long) {
    val meta by vm.itemMetadata.collectAsStateWithLifecycle()
    val match by vm.itemMatch.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    HorizontalDivider()
    Column(Modifier.heightIn(max = 260.dp).verticalScrollCompat()) {
        Text("Metadata: ${match?.status ?: "not matched yet"}" + (match?.let { m -> " · ${m.provider ?: "—"}:${m.externalId ?: "—"} · confidence ${"%.2f".format(Locale.US, m.confidence)}" } ?: "") +
            (match?.lastError?.let { " · error: $it" } ?: ""), fontSize = 12.sp)
        meta?.let { m ->
            Text("${m.title}${m.originalTitle?.takeIf { o -> o != m.title }?.let { " / $it" } ?: ""} (${m.year ?: "?"}) · ${m.genres.joinToString()} · lang ${m.originalLanguage ?: "?"}" +
                (m.runtimeMinutes?.let { " · ${it}min" } ?: "") + (m.rating?.let { " · ★$it" } ?: "") + (m.imdbId?.let { " · $it" } ?: ""), fontSize = 11.sp)
            m.overview?.let { Text(it.take(300), fontSize = 11.sp) }
            Text("poster: ${m.posterUrl ?: "—"}", fontSize = 10.sp)
            Text("backdrop: ${m.backdropUrl ?: "—"}", fontSize = 10.sp)
            if (m.cast.isNotEmpty()) Text("cast: ${m.cast.take(6).joinToString { c -> c.name + (c.character?.let { " ($it)" } ?: "") }}", fontSize = 10.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.rematch(itemId) }) { Text("Rematch", fontSize = 12.sp) }
            TextButton(onClick = { vm.reject(itemId) }) { Text("Reject / no metadata", fontSize = 12.sp) }
        }
        val candidates = results.ifEmpty { match?.candidates.orEmpty() }
        candidates.forEach { c ->
            Row {
                Text(
                    "${"%.2f".format(Locale.US, c.confidence)} ${c.candidate.title} (${c.candidate.year ?: "?"}) ${c.candidate.ref.type} " +
                        "[${c.candidate.ref.provider}:${c.candidate.ref.id}] t=${"%.2f".format(Locale.US, c.breakdown.title)} y=${c.breakdown.year} l=${c.breakdown.language}" +
                        (if (c.breakdown.ambiguityPenalty > 0) " ambiguous" else "") + (c.breakdown.capped?.let { " ($it)" } ?: ""),
                    fontSize = 10.sp, modifier = Modifier.weight(1f).padding(top = 10.dp),
                )
                TextButton(onClick = { vm.confirm(itemId, c.candidate.ref) }) { Text("Use this", fontSize = 11.sp) }
            }
        }
        Row {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("search metadata as…") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.searchMetadata(itemId, query) }) { Text("Search") }
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))
