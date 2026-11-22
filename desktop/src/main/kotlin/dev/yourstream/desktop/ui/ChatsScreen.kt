package dev.yourstream.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.desktop.data.ChatRow
import dev.yourstream.desktop.data.DesktopIndexStore
import dev.yourstream.desktop.library.IndexRunner
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.telegram.api.TelegramChatRepository
import dev.yourstream.telegram.api.model.ChatFilter
import dev.yourstream.telegram.api.model.ChatListState
import dev.yourstream.telegram.api.model.ChatListType
import kotlinx.coroutines.launch

/**
 * Choose the chats that hold your videos. Added chats are indexed in the background; removing
 * one takes its titles out of the library (the messages in Telegram are not touched).
 */
@Composable
fun ChatsScreen(chats: TelegramChatRepository, store: DesktopIndexStore, indexer: IndexRunner, mediaCount: Map<Long, Int>) {
    var filter by rememberSaveable { mutableStateOf(ChatFilter.CHANNELS) }
    var query by rememberSaveable { mutableStateOf("") }
    val list by remember(filter) { chats.chatList(ChatListType.Main, filter) }.collectAsState(ChatListState.initial(ChatListType.Main))
    val added by store.chatRows.collectAsState()
    val current by indexer.current.collectAsState()
    val progress by indexer.progressByChat.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { chats.loadAtLeast(ChatListType.Main, 200) }

    val addedById = added.associateBy { it.chatId }
    val shown = list.chats.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Chats", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f))
            listOf(ChatFilter.CHANNELS to "Channels", ChatFilter.GROUPS to "Groups", ChatFilter.PRIVATE to "Private", ChatFilter.ALL to "All").forEach { (f, label) ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(label) })
            }
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Find a chat") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.width(260.dp))
        }
        if (added.isNotEmpty()) {
            Text("In your library", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
            added.forEach { row ->
                AddedChat(row, mediaCount[row.chatId] ?: 0, running = current == row.chatId, pages = progress[row.chatId]?.messagesSeen,
                    onPause = { scope.launch { store.setPaused(row.chatId, !row.paused); if (row.paused) indexer.kick() else indexer.stop(row.chatId) } },
                    onRemove = { scope.launch { indexer.stop(row.chatId); store.removeChat(row.chatId) } })
            }
            Text("Add more", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown.filter { it.id !in addedById }, key = { it.id }) { chat ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(chat.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(chat.category.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { scope.launch { store.addChat(chat.id, chat.title); indexer.kick() } }) { Text("Add") }
                    }
                }
            }
            item {
                if (list.isLoading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (list.hasMore) OutlinedButton(onClick = { scope.launch { chats.loadMore(ChatListType.Main) } }, modifier = Modifier.fillMaxWidth()) { Text("Load more chats") }
            }
        }
    }
}

@Composable
private fun AddedChat(row: ChatRow, files: Int, running: Boolean, pages: Int?, onPause: () -> Unit, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(row.title ?: "Chat", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        row.paused -> "Paused"
                        running -> "Indexing… ${pages?.let { "$it messages read" } ?: ""}"
                        row.state == IndexState.FAILED -> "Could not read this chat${row.error?.let { ": $it" } ?: ""}"
                        else -> "Up to date"
                    }
                    Text("$files videos · $status", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onPause) { Text(if (row.paused) "Resume" else "Pause") }
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}
