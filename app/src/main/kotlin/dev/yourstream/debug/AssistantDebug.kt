package dev.yourstream.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import dev.yourstream.assistant.engine.AssistantResponse
import dev.yourstream.assistant.engine.MediaAssistant
import dev.yourstream.library.api.PlaybackTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Stage-1 debug UI for the local natural-language assistant (deterministic, offline). */
@HiltViewModel
class AssistantDebugViewModel @Inject constructor(private val assistant: MediaAssistant) : ViewModel() {
    val text = MutableStateFlow("")
    val response = MutableStateFlow<AssistantResponse?>(null)
    val error = MutableStateFlow<String?>(null)

    fun setText(t: String) { text.value = t }

    fun ask(t: String = text.value) {
        text.value = t
        viewModelScope.launch {
            runCatching { assistant.ask(t) }
                .onSuccess { response.value = it; error.value = null }
                .onFailure { error.value = it.message ?: it.toString() }
        }
    }

    companion object {
        val EXAMPLES = listOf(
            "show Tamil movies", "find Doraemon episodes", "continue watching Ben 10", "show unwatched comedy movies",
            "find movies from 2005", "show Telugu movies I haven't watched", "find season 2 episode 10", "what should I watch next?",
            "unwatched Tamil movies", "play ben 10", "90s hindi comedies", "top 5 rated action movies",
        )
    }
}

@Composable
fun AssistantPane(vm: AssistantDebugViewModel, openItem: (Long) -> Unit, onPlay: (PlaybackTarget, Long?) -> Unit) {
    val text by vm.text.collectAsStateWithLifecycle()
    val response by vm.response.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(text, vm::setText, label = { Text("Ask (offline, no AI service)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { vm.ask() }) { Text("Ask") }
            AssistantDebugViewModel.EXAMPLES.forEach { q -> TextButton(onClick = { vm.ask(q) }) { Text(q, fontSize = 11.sp) } }
        }
        error?.let { Text("Error: $it", fontSize = 12.sp) }
        response?.let { r ->
            Text(r.message, fontSize = 14.sp)
            val i = r.interpretation
            Text("intent ${i.intent} · confidence ${"%.2f".format(i.confidence)} · ${i.source.name.lowercase()} · ${r.tookMs} ms", fontSize = 11.sp)
            Text("entities: ${i.entities}", fontSize = 10.sp)
            Text("plan: ${r.plan}", fontSize = 10.sp)
            if (i.notes.isNotEmpty()) Text("read as: " + i.notes.joinToString(" · "), fontSize = 10.sp)
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(r.items) { _, it ->
                    Row(Modifier.fillMaxWidth().clickable { openItem(it.itemId) }.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                it.title + (it.year?.let { y -> " ($y)" } ?: "") +
                                    (if (it.season != null || it.episode != null) "  S${it.season ?: "?"}E${it.episode ?: "?"}" else "") +
                                    (it.episodeTitle?.let { t -> " – $t" } ?: ""),
                                fontSize = 13.sp,
                            )
                            Text("#${it.itemId} ${it.kind}" + (it.progress?.let { p -> " · ${(p * 100).toInt()}%" } ?: "") + (it.reason?.let { r -> " · $r" } ?: ""), fontSize = 10.sp)
                        }
                        TextButton(onClick = { onPlay(PlaybackTarget(it.itemId, it.episodeId), null) }) { Text("▶", fontSize = 11.sp) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
