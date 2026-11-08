package dev.yourstream.debug

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatFilter
import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.api.model.TelegramStatus
import java.text.DateFormat
import java.util.Date

// Stage-1 debug UI: plain, functional, intentionally unstyled. Do not polish; Stage 2 replaces it.

@Composable
fun DebugApp(
    vm: DebugViewModel,
    indexVm: IndexDebugViewModel,
    libraryVm: LibraryDebugViewModel,
    catalogVm: CatalogDebugViewModel,
    onPlay: (dev.yourstream.library.api.PlaybackTarget, Long?) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val status by vm.status.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val auth by vm.authState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val showLogs by vm.showLogs.collectAsStateWithLifecycle()
    val selected by vm.selectedChatId.collectAsStateWithLifecycle()

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp)) {
                Text("TDLib: ${status.label()} | network: $connection", fontSize = 12.sp)
                Text("auth: ${auth.label()}${if (busy) " | working…" else ""}", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = vm::reconnect) { Text("Reconnect") }
                    TextButton(onClick = vm::restart) { Text("Restart TDLib") }
                    TextButton(onClick = vm::toggleLogs) { Text(if (showLogs) "Hide logs" else "Logs") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("Telegram", "Index & search", "Library", "Catalogs").forEachIndexed { i, name ->
                        TextButton(onClick = { tab = i }) { Text(if (tab == i) "[$name]" else name) }
                    }
                }
                message?.let {
                    Text("Error: $it", color = Color(0xFFB00020), modifier = Modifier.clickable { vm.dismissMessage() })
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                when {
                    showLogs -> {
                        BackHandler { vm.toggleLogs() }
                        LogsPane(vm)
                    }
                    tab == 1 -> IndexPane(indexVm)
                    tab == 2 -> LibraryPane(libraryVm, onPlay)
                    tab == 3 -> CatalogPane(catalogVm) { id -> libraryVm.open(id); tab = 2 }
                    selected != null -> {
                        BackHandler { vm.clearSelection() }
                        ChatDetailsPane(vm, indexVm)
                    }
                    else -> AuthOrChats(vm, auth)
                }
            }
        }
    }
}

@Composable
private fun AuthOrChats(vm: DebugViewModel, auth: AuthState) {
    when (auth) {
        is AuthState.NotConfigured -> Form {
            Text("Telegram is not configured.")
            Text(auth.reason)
            Text("Add telegram.apiId and telegram.apiHash to local.properties and rebuild. See docs/TELEGRAM_SETUP.md.")
        }
        AuthState.WaitPhoneNumber -> PhoneForm(vm)
        is AuthState.WaitCode -> Form {
            val info = auth.info
            Text("Code sent via ${info.type}" + (info.codeLength?.let { " ($it digits)" } ?: ""))
            info.nextType?.let { Text("Can resend via $it" + if (info.timeoutSeconds > 0) " after ${info.timeoutSeconds}s" else "") }
            SingleField("Code", KeyboardType.Number, submit = "Submit code", onSubmit = vm::submitCode)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::resendCode, enabled = info.nextType != null) { Text("Resend code") }
                OutlinedButton(onClick = vm::requestQrLogin) { Text("Use QR instead") }
            }
        }
        is AuthState.WaitPassword -> Form {
            Text("Two-step verification is on.")
            if (auth.hint.isNotBlank()) Text("Hint: ${auth.hint}")
            SingleField("Password", KeyboardType.Password, secret = true, submit = "Submit password", onSubmit = vm::submitPassword)
        }
        AuthState.WaitEmailAddress -> Form {
            Text("Telegram requires a login email for this account.")
            SingleField("Email", KeyboardType.Email, submit = "Submit email", onSubmit = vm::submitEmail)
        }
        is AuthState.WaitEmailCode -> Form {
            Text("Code sent to ${auth.emailPattern}")
            SingleField("Email code", KeyboardType.Number, submit = "Submit code", onSubmit = vm::submitEmailCode)
        }
        is AuthState.WaitOtherDeviceConfirmation -> Form {
            Text("Scan with Telegram on your phone: Settings > Devices > Link Desktop Device.")
            QrImage(auth.link)
            Text(auth.link, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        AuthState.WaitRegistration -> Form {
            Text("This number has no Telegram account. Sign up with an official Telegram app first, then log in here.")
        }
        AuthState.Ready -> ChatsPane(vm)
        is AuthState.Unsupported -> Form { Text("Unsupported login step: ${auth.name}. Finish it in an official app.") }
        else -> Form { Text("Waiting for TDLib… (${auth.label()})") }
    }
}

@Composable
private fun PhoneForm(vm: DebugViewModel) = Form {
    Text("Log in to Telegram")
    SingleField("Phone (+country code)", KeyboardType.Phone, submit = "Send code", onSubmit = vm::submitPhone)
    OutlinedButton(onClick = vm::requestQrLogin) { Text("Log in with QR code") }
}

@Composable
private fun ChatsPane(vm: DebugViewModel) {
    val user by vm.currentUser.collectAsStateWithLifecycle()
    val state by vm.chatList.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val listType by vm.listType.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row {
            Text(
                "Logged in as ${user?.displayName ?: "…"}" + (user?.username?.let { " @$it" } ?: "") + " (id ${user?.id ?: "?"})",
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
            TextButton(onClick = vm::logOut) { Text("Log out") }
        }
        Row {
            ChatFilter.entries.forEach { f ->
                TextButton(onClick = { vm.setFilter(f) }) { Text(if (f == filter) "[${f.name}]" else f.name, fontSize = 12.sp) }
            }
        }
        Row {
            listOf(ChatListType.Main to "Main", ChatListType.Archive to "Archive").forEach { (type, name) ->
                TextButton(onClick = { vm.setListType(type) }) { Text(if (type == listType) "[$name]" else name, fontSize = 12.sp) }
            }
        }
        Text(
            "${state.chats.size} chats shown" + (if (state.isLoading) ", loading…" else "") +
                (if (!state.hasMore) ", all loaded" else "") + (state.error?.let { ", error: ${it.message}" } ?: ""),
            fontSize = 12.sp,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(state.chats, key = { it.id }) { chat -> ChatRow(chat) { vm.selectChat(chat.id) } }
            item {
                if (state.hasMore) {
                    Button(onClick = vm::loadMore, enabled = !state.isLoading, modifier = Modifier.padding(8.dp)) {
                        Text(if (state.isLoading) "Loading…" else "Load more")
                    }
                } else {
                    Text("— end of list —", modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: TelegramChat, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(chat.title.ifBlank { "(no title)" })
        Text(
            "${chat.kind.label()} · id ${chat.id}" + (if (chat.unreadCount > 0) " · ${chat.unreadCount} unread" else "") +
                (if (chat.positionIn(ChatListType.Main)?.isPinned == true) " · pinned" else ""),
            fontSize = 11.sp,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ChatDetailsPane(vm: DebugViewModel, indexVm: IndexDebugViewModel) {
    val details by vm.details.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = vm::clearSelection) { Text("< Back to chats") }
        when (val d = details) {
            null, DebugViewModel.Details.Loading -> Text("Loading chat…")
            is DebugViewModel.Details.Failed -> Text("Could not load chat: ${d.message}")
            is DebugViewModel.Details.Loaded -> {
                val info = d.details
                val chat = info.chat
                Field("Title", chat.title)
                Field("Chat id", chat.id.toString())
                Field("Type", chat.kind.label())
                Field("Category", chat.category.name)
                Field("Username", info.username?.let { "@$it" })
                Field("Members", info.memberCount?.toString())
                Field("Forum", if (info.isForum) "yes" else "no")
                Field("Protected content", if (chat.hasProtectedContent) "yes (saving/forwarding disabled)" else "no")
                Field("Unread", chat.unreadCount.toString())
                Field("Last message", chat.lastMessageDate?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) })
                Field("Created / joined", info.createdAt?.let { DateFormat.getDateInstance().format(Date(it * 1000)) })
                Field("Linked chat id", info.linkedChatId?.toString())
                Field("Lists", chat.positions.joinToString { "${it.list}${if (it.isPinned) " (pinned)" else ""}" })
                info.user?.let { Field("User", "${it.displayName} (id ${it.id}${if (it.isBot) ", bot" else ""})") }
                Field("Description", info.description)
                ChatIndexControls(indexVm, chat.id)
            }
        }
    }
}

@Composable
private fun LogsPane(vm: DebugViewModel) {
    val lines by vm.logLines.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines.asReversed()) { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Text("$label: ${value ?: "—"}", modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun Form(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SingleField(
    label: String,
    keyboard: KeyboardType,
    submit: String,
    secret: Boolean = false,
    onSubmit: (String) -> Unit,
) {
    // Secrets (code, password) are not saved across process death.
    var value by if (secret) remember { mutableStateOf("") } else rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(value) }, enabled = value.isNotEmpty()) { Text(submit) }
}

@Composable
private fun QrImage(link: String) {
    val bitmap = remember(link) {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 512, 512)
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
            for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }.asImageBitmap()
    }
    Image(bitmap, contentDescription = "Login QR code", modifier = Modifier.size(260.dp))
}

private fun TelegramStatus.label(): String = when (this) {
    is TelegramStatus.Failed -> "failed (${error.message})"
    is TelegramStatus.NotConfigured -> "not configured"
    else -> this::class.simpleName ?: toString()
}

private fun AuthState.label(): String = when (this) {
    is AuthState.WaitCode -> "WaitCode"
    is AuthState.WaitPassword -> "WaitPassword"
    is AuthState.WaitEmailCode -> "WaitEmailCode"
    is AuthState.WaitOtherDeviceConfirmation -> "WaitQrScan"
    is AuthState.NotConfigured -> "NotConfigured"
    is AuthState.Unsupported -> "Unsupported($name)"
    else -> toString()
}

private fun ChatKind.label(): String = when (this) {
    is ChatKind.Private -> "private (user $userId)"
    is ChatKind.Secret -> "secret chat (user $userId)"
    is ChatKind.BasicGroup -> "group (basic $basicGroupId)"
    is ChatKind.Supergroup -> "group (supergroup $supergroupId)"
    is ChatKind.Channel -> "channel (supergroup $supergroupId)"
}
