package dev.yourstream.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.desktop.update.DesktopUpdates
import kotlinx.coroutines.launch

/** The bar at the top of the app when a newer version is out (until dismissed). */
@Composable
fun UpdateBanner(updates: DesktopUpdates) {
    val state by updates.state.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return
    val show = state is DesktopUpdates.State.Available || state is DesktopUpdates.State.Downloading || state is DesktopUpdates.State.Ready
    if (!show) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("update-banner")) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            UpdateActions(updates, state, Modifier.weight(1f))
            TextButton(onClick = { dismissed = true }) { Text("Later") }
        }
    }
}

/** Settings → Updates. */
@Composable
fun UpdateSection(updates: DesktopUpdates, installedVersion: String) {
    val state by updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Installed: $installedVersion", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (state) {
            DesktopUpdates.State.Idle, is DesktopUpdates.State.UpToDate, is DesktopUpdates.State.Failed, DesktopUpdates.State.Checking -> {
                (state as? DesktopUpdates.State.UpToDate)?.let { Text("You have the latest version.") }
                (state as? DesktopUpdates.State.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { scope.launch { updates.check() } }, enabled = state != DesktopUpdates.State.Checking) {
                    Text(if (state == DesktopUpdates.State.Checking) "Checking…" else "Check for updates")
                }
            }
            else -> UpdateActions(updates, state, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UpdateActions(updates: DesktopUpdates, state: DesktopUpdates.State, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            is DesktopUpdates.State.Available -> {
                Text("YourStream ${state.release.version} is available.", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                Button(onClick = { scope.launch { updates.download(state.release) } }) { Text("Download (${state.release.size shr 20} MB)") }
            }
            is DesktopUpdates.State.Downloading -> {
                Text("Downloading YourStream ${state.release.version}…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                    modifier = Modifier.width(220.dp),
                )
                Text("${state.done shr 20} / ${state.total shr 20} MB", fontSize = 12.sp)
            }
            is DesktopUpdates.State.Ready -> {
                Text("YourStream ${state.release.version} is ready (checked).", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                Button(onClick = { updates.install(state) }) { Text("Restart and install") }
            }
            is DesktopUpdates.State.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}
