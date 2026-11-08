package dev.yourstream.debug

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yourstream.catalog.api.CatalogType

// Stage-1 debug UI for catalog providers. Plain on purpose. Catalog titles are not playable:
// only the "in library" link leads to something watchable (the user's Telegram files).

@Composable
fun CatalogPane(vm: CatalogDebugViewModel, openLibraryItem: (Long) -> Unit) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val s = selected
    if (s != null) {
        BackHandler { vm.close() }
        DetailPane(vm, s.first, s.second, openLibraryItem)
    } else {
        ListPane(vm, openLibraryItem)
    }
}

@Composable
private fun ListPane(vm: CatalogDebugViewModel, openLibraryItem: (Long) -> Unit) {
    val availability by vm.availability.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val request by vm.request.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        availability.forEach { a ->
            Text("${a.displayName}: ${a.state} · ${a.catalogCount} catalogs · ${a.languages.joinToString(",").ifEmpty { "mixed" }}" +
                (a.error?.let { " · $it" } ?: ""), fontSize = 11.sp)
        }
        if (availability.isEmpty()) Text("No catalog providers configured (catalog.providers). Local library only.", fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.refreshAvailability() }) { Text(if (busy) "working…" else "re-check providers", fontSize = 12.sp) }
            (listOf<CatalogType?>(null) + CatalogType.entries).forEach { t ->
                TextButton(onClick = { vm.setType(t) }) { Text((t?.name ?: "ANY").let { if (t == request.type) "[$it]" else it }, fontSize = 12.sp) }
            }
            (listOf<String?>(null) + vm.tags).forEach { tag ->
                TextButton(onClick = { vm.setTag(tag) }) { Text((tag ?: "any tag").let { if (tag == request.tag) "[$it]" else it }, fontSize = 12.sp) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.setLanguage(null) }) { Text(if (request.language == null) "[all]" else "all", fontSize = 12.sp) }
            languages.forEach { (lang, providers) ->
                val label = "${lang.name}${if (providers.isEmpty()) "°" else ""}"
                TextButton(onClick = { vm.setLanguage(lang.code) }) { Text(if (request.language == lang.code) "[$label]" else label, fontSize = 12.sp) }
            }
        }
        Row {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search catalogs") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.search(query) }) { Text("Go") }
        }
        Text("${listing.rows.size} titles · source: ${listing.source}${if (listing.stale) " (stale cache)" else ""} · ° = no provider, local only", fontSize = 11.sp)
        listing.skipped.forEach { Text("skipped: $it", fontSize = 10.sp) }
        LazyColumn(Modifier.weight(1f)) {
            items(listing.rows, key = { "${it.item.ref.provider}|${it.item.ref.id}" }) { row ->
                Column(Modifier.fillMaxWidth().clickable { vm.open(row) }.padding(vertical = 5.dp)) {
                    Text(row.item.title + (row.item.year?.let { " ($it)" } ?: "") + (if (row.item.posterUrl != null) "  🖼" else ""), fontSize = 13.sp)
                    Text(
                        listOfNotNull(
                            row.item.ref.type.name, row.item.ref.provider.value, row.item.ref.imdbId, row.item.catalogId,
                            row.item.languages.joinToString(",").ifEmpty { null },
                            row.presence?.let { "IN LIBRARY #${it.itemId} (${it.availableFiles} file(s), by ${it.matchedBy})" } ?: "not in library",
                        ).joinToString(" · "),
                        fontSize = 11.sp,
                    )
                    row.presence?.let { p -> TextButton(onClick = { openLibraryItem(p.itemId) }) { Text("Open in library", fontSize = 11.sp) } }
                }
                HorizontalDivider()
            }
            if (listing.next != null) item { TextButton(onClick = vm::more) { Text("more") } }
        }
    }
}

@Composable
private fun DetailPane(vm: CatalogDebugViewModel, row: CatalogRow, meta: dev.yourstream.catalog.api.Sourced<dev.yourstream.catalog.api.CatalogMetadata>?, openLibraryItem: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = vm::close) { Text("< Back") }
        Text("${row.item.title}${row.item.year?.let { " ($it)" } ?: ""}")
        Text("ref: ${row.item.ref}", fontSize = 10.sp)
        row.presence?.let { p ->
            Text("On Telegram: library item #${p.itemId} \"${p.title}\", ${p.availableFiles} file(s), matched by ${p.matchedBy}", fontSize = 12.sp)
            TextButton(onClick = { openLibraryItem(p.itemId) }) { Text("Open in library") }
        } ?: Text("Not in your library. A catalog title is information only; nothing here can be played.", fontSize = 12.sp)
        HorizontalDivider()
        if (meta == null) {
            Text("loading metadata…", fontSize = 12.sp)
        } else {
            val m = meta.value
            Text("metadata source: ${meta.source.label()}${if (meta.fromCache) " (cache)" else ""}${if (meta.stale) " (stale)" else ""}${if (m.partial) " · partial" else ""}", fontSize = 11.sp)
            Text(m.title + (m.year?.let { " ($it)" } ?: ""), fontSize = 13.sp)
            m.description?.let { Text(it, fontSize = 12.sp) }
            Text(
                listOfNotNull(
                    m.genres.joinToString().ifEmpty { null }, m.releaseDate, m.originalLanguage, m.runtimeMinutes?.let { "$it min" },
                    m.rating?.let { "★ $it" },
                ).joinToString(" · "),
                fontSize = 11.sp,
            )
            if (m.cast.isNotEmpty()) Text("cast: ${m.cast.take(8).joinToString()}", fontSize = 11.sp)
            if (m.directors.isNotEmpty()) Text("directors: ${m.directors.joinToString()}", fontSize = 11.sp)
            m.posterUrl?.let { Text("poster: $it", fontSize = 10.sp) }
        }
        val attribution = vm.availability.collectAsStateWithLifecycle().value.firstOrNull { it.provider == row.item.ref.provider }?.attribution
        attribution?.let { Text(it, fontSize = 10.sp) }
    }
}
