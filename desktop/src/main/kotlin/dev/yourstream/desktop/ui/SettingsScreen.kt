package dev.yourstream.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.desktop.AppGraph
import dev.yourstream.desktop.playback.Vlc
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsState()
    var key by remember { mutableStateOf(settings.tmdbKey) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        Section("Appearance") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dark theme", modifier = Modifier.weight(1f))
                Switch(settings.darkTheme, { on -> graph.settings.update { it.copy(darkTheme = on) } })
            }
        }

        Section("Posters (TMDB)") {
            Text(
                "Posters come from Cinemeta without any setup. For better matches, add your free TMDB API key (themoviedb.org → Settings → API). Only titles and years are sent.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it.trim() }, singleLine = true, label = { Text("TMDB API key or read token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                Button(onClick = { graph.settings.update { it.copy(tmdbKey = key) } }, enabled = key != settings.tmdbKey) { Text("Save") }
            }
        }

        Section("Video player") {
            val vlc = remember { Vlc.available(graph.logger) }
            Text(if (vlc) "VLC found: videos play in the app." else "VLC was not found. Install VLC (64-bit) to play videos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!vlc) OutlinedButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(Vlc.DOWNLOAD_URL)) } }) { Text("Get VLC") }
        }

        Section("Storage") {
            Text("Your Telegram session, index and cache are kept in:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(graph.dataDir.absolutePath, fontSize = 13.sp)
            OutlinedButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(graph.dataDir) } }) { Text("Open folder") }
        }

        Section("Account") {
            Text("Signing out ends this PC's Telegram session and removes it from this PC.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
        }

        Text("YourStream ${graph.build.version} for desktop · GPL-3.0 · Plays files you already have access to; hosts no content.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("You can sign in again any time. Your library is read again after signing in.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; scope.launch { graph.auth.logOut() } }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().widthIn(max = 820.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}
