package dev.yourstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.desktop.AppGraph
import dev.yourstream.desktop.playback.Vlc
import kotlinx.coroutines.launch

/** Settings' sections, in the list on the left. */
enum class SettingsSection(val label: String, val icon: ImageVector) {
    APPEARANCE("Appearance", Icons.Default.Palette),
    CHATS("Telegram chats", Icons.Default.Forum),
    ADDONS("Add-ons", Icons.Default.Extension),
    PLAYER("Player", Icons.Default.PlayCircle),
    POSTERS("Posters", Icons.Default.Image),
    UPDATES("Updates", Icons.Default.SystemUpdate),
    STORAGE("Storage", Icons.Default.Folder),
    ACCOUNT("Account", Icons.Default.AccountCircle),
}

/**
 * Settings, like the Android app's (and Nuvio's): the sections in a list on the left, the chosen
 * one on the right. Telegram chats and add-ons are managed here too.
 */
@Composable
fun SettingsScreen(graph: AppGraph, section: SettingsSection, onSection: (SettingsSection) -> Unit, mediaCount: Map<Long, Int>) {
    Row(Modifier.fillMaxSize().padding(start = LocalNavInset.current + 16.dp, top = 36.dp)) {
        Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PageTitle("Settings", Modifier.padding(bottom = 18.dp, start = 8.dp))
            SettingsSection.entries.forEach { s -> SectionItem(s, s == section) { onSection(s) } }
            Text(
                "YourStream ${graph.build.version}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 18.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp)) {
            when (section) {
                SettingsSection.CHATS -> ChatsScreen(graph.chats, graph.index, graph.indexer, mediaCount)
                SettingsSection.ADDONS -> AddonsScreen(graph.addons)
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 40.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Heading(section.label, 26.sp, Modifier.padding(bottom = 4.dp))
                    when (section) {
                        SettingsSection.APPEARANCE -> Appearance(graph)
                        SettingsSection.PLAYER -> Player(graph)
                        SettingsSection.POSTERS -> Posters(graph)
                        SettingsSection.UPDATES -> Card { UpdateSection(graph.updates, graph.build.version) }
                        SettingsSection.STORAGE -> Storage(graph)
                        SettingsSection.ACCOUNT -> Account(graph)
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionItem(s: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.small else RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().then(if (selected && LocalMangaStyle.current) Modifier.inkPanel(shape, shadow = 2.dp) else Modifier).clip(shape)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.surfaceContainerHigh
                    hovered -> MaterialTheme.colorScheme.surfaceContainer
                    else -> MaterialTheme.colorScheme.background
                },
            )
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp).testTag("settings:${s.name}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(s.icon, null, tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** A rounded card holding one group of settings. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.medium else RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().widthIn(max = 820.dp).inkPanel(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** A setting with a switch: name and explanation on the left. */
@Composable
private fun Toggle(title: String, text: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            text?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(on, onChange)
    }
}

@Composable
private fun Note(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

@Composable
private fun Appearance(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsState()
    Card {
        Toggle("Manga style", "Comic lettering, inked panels and screentones, like the Android app. Off: the classic black look.", settings.mangaStyle) { on -> graph.settings.update { it.copy(mangaStyle = on) } }
        Toggle("Dark theme", null, settings.darkTheme) { on -> graph.settings.update { it.copy(darkTheme = on) } }
    }
}

@Composable
private fun Player(graph: AppGraph) {
    val vlc = remember { Vlc.available(graph.logger) }
    Card {
        Text(if (vlc) "VLC found: videos play in the app." else "VLC was not found. Install VLC (64-bit) to play videos.", fontWeight = FontWeight.SemiBold)
        if (!vlc) PillButton("Get VLC", { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(Vlc.DOWNLOAD_URL)) } }, primary = false)
        Note("Keys while playing: Space play/pause, ←/→ 10 s (Shift 60 s), ↑/↓ volume, A audio, S subtitles, F fullscreen, N next episode, ? all keys.")
    }
}

@Composable
private fun Posters(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsState()
    var key by remember { mutableStateOf(settings.tmdbKey) }
    Card {
        Text("TMDB", fontWeight = FontWeight.SemiBold)
        Note("Posters come from Cinemeta without any setup. For better matches, add your free TMDB API key (themoviedb.org → Settings → API). Only titles and years are sent.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(key, { key = it.trim() }, singleLine = true, label = { Text("TMDB API key or read token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
            PillButton("Save", { graph.settings.update { it.copy(tmdbKey = key) } }, primary = key != settings.tmdbKey)
        }
    }
}

@Composable
private fun Storage(graph: AppGraph) {
    Card {
        Note("Your Telegram session, index and cache are kept in:")
        Text(graph.dataDir.absolutePath, fontSize = 13.sp)
        PillButton("Open folder", { runCatching { java.awt.Desktop.getDesktop().open(graph.dataDir) } }, primary = false)
    }
}

@Composable
private fun Account(graph: AppGraph) {
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Card {
        Text("Telegram", fontWeight = FontWeight.SemiBold)
        Note("Signing out ends this PC's Telegram session and removes it from this PC.")
        PillButton("Sign out", { confirm = true }, primary = false)
    }
    Note("YourStream ${graph.build.version} for desktop · GPL-3.0 · Plays files you already have access to; hosts no content.")
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Sign out?") },
        text = { Text("You can sign in again any time. Your library is read again after signing in.") },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { graph.auth.logOut() } }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
