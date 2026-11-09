package dev.yourstream.ui.screens.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the in-app update is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    /** A newer release with an APK exists. */
    data class Available(val version: String, val notes: String, val sizeBytes: Long) : UpdateState
    data class Downloading(val version: String, val fraction: Float?) : UpdateState
    /** Downloaded; the system installer was (or can be) opened. */
    data class Ready(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * New versions of the app from its GitHub Releases. Implemented by the app module (it knows the
 * repository, the installed version and how to hand an APK to the system installer).
 */
interface AppUpdates {
    val installedVersion: String
    val state: StateFlow<UpdateState>

    /** Asks GitHub for the latest release ([quiet]: on app start, at most every few hours). */
    suspend fun check(quiet: Boolean = false)

    /** Downloads the available APK, then opens the installer. */
    suspend fun downloadAndInstall()
}

@HiltViewModel
class UpdateViewModel @Inject constructor(private val updates: AppUpdates) : ViewModel() {
    val state: StateFlow<UpdateState> = updates.state
    val installedVersion: String get() = updates.installedVersion

    /** The release the user said "Later" to in this session (no dialog again for it). */
    private val _dismissed = MutableStateFlow<String?>(null)
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    fun checkOnStart() = viewModelScope.launch { updates.check(quiet = true) }
    fun check() = viewModelScope.launch { updates.check(quiet = false) }
    fun install() = viewModelScope.launch { updates.downloadAndInstall() }
    fun later(version: String) { _dismissed.value = version }
}

/** Checks once when the app starts and offers a newer version in a dialog. */
@Composable
fun UpdatePrompt(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dismissed by vm.dismissed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.checkOnStart() }
    val version = when (val s = state) {
        is UpdateState.Available -> s.version
        is UpdateState.Downloading -> s.version
        else -> null
    }
    if (version == null || version == dismissed) return
    UpdateDialog(state, onUpdate = vm::install, onLater = { vm.later(version) })
}

@Composable
fun UpdateDialog(state: UpdateState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val downloading = state as? UpdateState.Downloading
    val available = state as? UpdateState.Available
    AlertDialog(
        onDismissRequest = { if (downloading == null) onLater() },
        modifier = Modifier.testTag("update-dialog"),
        title = { Text(stringResource(R.string.update_title, available?.version ?: downloading?.version.orEmpty())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (downloading != null) {
                    Text(stringResource(R.string.update_downloading))
                    downloading.fraction?.let { f -> LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.update_message), style = MaterialTheme.typography.bodyMedium)
                    available?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Text(notes.take(1500), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        },
        confirmButton = {
            if (downloading == null) Button(onClick = onUpdate, modifier = Modifier.testTag("update-now")) { Text(stringResource(R.string.update_now)) }
        },
        dismissButton = {
            if (downloading == null) TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
        },
    )
}

/** One line for Settings: "Version 0.4.0 · Up to date" / "Update to 0.5.0" / "Downloading 40 %". */
@Composable
fun updateSummary(installed: String, state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.settings_version, installed)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, installed)
    is UpdateState.Available -> stringResource(R.string.update_available, state.version)
    is UpdateState.Downloading -> stringResource(R.string.update_downloading_percent, ((state.fraction ?: 0f) * 100).toInt())
    is UpdateState.Ready -> stringResource(R.string.update_ready, state.version)
    is UpdateState.Failed -> stringResource(R.string.update_failed, state.message)
}
