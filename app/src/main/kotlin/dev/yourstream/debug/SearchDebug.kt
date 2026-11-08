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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.search.core.ItemKind
import dev.yourstream.search.core.SearchFilters
import dev.yourstream.search.core.SearchHit
import dev.yourstream.search.core.SearchPage
import dev.yourstream.search.core.SearchRequest
import dev.yourstream.search.core.SearchSort
import dev.yourstream.search.core.WatchState
import dev.yourstream.search.engine.LibrarySearch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Stage-1 debug UI for the offline search engine. */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchDebugViewModel @Inject constructor(private val search: LibrarySearch) : ViewModel() {
    val request = MutableStateFlow(SearchRequest(limit = 40))
    val page = MutableStateFlow<SearchPage?>(null)
    val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    private var job: Job? = null

    fun setText(text: String) = update(request.value.copy(text = text, offset = 0), debounce = true)
    fun cycleKind() {
        val kinds = listOf<ItemKind?>(null) + ItemKind.entries
        val next = kinds[(kinds.indexOf(request.value.filters.kinds.firstOrNull()) + 1) % kinds.size]
        update(request.value.copy(filters = request.value.filters.copy(kinds = setOfNotNull(next)), offset = 0))
    }
    fun cycleWatch() = update(request.value.copy(filters = request.value.filters.copy(watch = WatchState.entries[(request.value.filters.watch.ordinal + 1) % WatchState.entries.size]), offset = 0))
    fun cycleSort() = update(request.value.copy(sort = SearchSort.entries[(request.value.sort.ordinal + 1) % SearchSort.entries.size], offset = 0))
    fun toggleReversed() = update(request.value.copy(reversed = !request.value.reversed, offset = 0))
    fun setFilters(f: SearchFilters) = update(request.value.copy(filters = f, offset = 0))

    fun more() {
        val next = page.value?.nextOffset ?: return
        viewModelScope.launch {
            val p = search.search(request.value.copy(offset = next))
            page.value = p
            hits.value = hits.value + p.hits
        }
    }

    private fun update(r: SearchRequest, debounce: Boolean = false) {
        request.value = r
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(150)
            val p = search.search(r)
            page.value = p
            hits.value = p.hits
            suggestions.value = if (r.text.isNotBlank()) search.suggest(r.text) else emptyList()
        }
    }

    init { update(request.value) }
}

@Composable
fun SearchPane(vm: SearchDebugViewModel, openItem: (Long) -> Unit) {
    val request by vm.request.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(request.text, vm::setText, label = { Text("Search (doremon, tom jerry, tamil comedy, ben 10 s2, lang:ta year:2020-2023 …)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (suggestions.isNotEmpty()) Text("suggest: " + suggestions.joinToString(", "), fontSize = 10.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = vm::cycleKind) { Text("kind: ${request.filters.kinds.firstOrNull() ?: "any"}", fontSize = 12.sp) }
            TextButton(onClick = vm::cycleWatch) { Text("watched: ${request.filters.watch}", fontSize = 12.sp) }
            TextButton(onClick = vm::cycleSort) { Text("sort: ${request.sort}", fontSize = 12.sp) }
            TextButton(onClick = vm::toggleReversed) { Text(if (request.reversed) "[reversed]" else "reverse", fontSize = 12.sp) }
            TextButton(onClick = { vm.setFilters(request.filters.copy(languages = if ("ta" in request.filters.languages) emptySet() else setOf("ta"))) }) {
                Text(if ("ta" in request.filters.languages) "[lang ta]" else "lang ta", fontSize = 12.sp)
            }
            TextButton(onClick = { vm.setFilters(request.filters.copy(minResolution = if (request.filters.minResolution == null) 1080 else null)) }) {
                Text(if (request.filters.minResolution != null) "[≥1080p]" else "≥1080p", fontSize = 12.sp)
            }
        }
        page?.let { p ->
            Text("${p.total} results · ${p.tookMicros / 1000.0} ms · " + p.interpretation.joinToString(" · "), fontSize = 11.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(hits, key = { it.itemId }) { h ->
                Column(Modifier.fillMaxWidth().clickable { openItem(h.itemId) }.padding(vertical = 4.dp)) {
                    Text("${h.title}${h.year?.let { " ($it)" } ?: ""}" + (h.season?.let { "  → S$it" } ?: "") + (h.episodeNumber?.let { "E$it" } ?: "") +
                        (if (h.episodeId != null) " ✓" else ""), fontSize = 13.sp)
                    Text(
                        "#${h.itemId} ${h.kind} · score ${"%.2f".format(h.score)} · " +
                            h.hits.joinToString { "${it.queryTerm}→${it.matched} ${it.kind.name.lowercase()} in ${it.field.name.lowercase()}" } +
                            (if (h.satisfied.isNotEmpty()) " · ✓ " + h.satisfied.joinToString { it::class.simpleName.orEmpty().lowercase() } else ""),
                        fontSize = 10.sp,
                    )
                }
                HorizontalDivider()
            }
            if (page?.nextOffset != null) item { TextButton(onClick = vm::more) { Text("more") } }
        }
    }
}
