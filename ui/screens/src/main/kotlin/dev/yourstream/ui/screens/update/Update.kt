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
    /** Android needs "Install unknown apps" allowed for YourStream; continues on return. */
    data class NeedsPermission(val version: String) : UpdateState
    /** Handed to Android's installer; its confirmation is on screen. */
    data class Installing(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * New versions of the app from its GitHub Releases, installed inside the app. Implemented by the
 * app module (it knows the repository, the installed version and the package installer).
 */
interface AppUpdates {
    val installedVersion: String
    val state: StateFlow<UpdateState>

    /** Asks GitHub for the latest release ([quiet]: on app start; failures stay silent). */
    suspend fun check(quiet: Boolean = false)

    /** Downloads the available APK (with progress), then hands it to Android's installer. */
    suspend fun downloadAndInstall()

    /** The app is in front again (e.g. back from allowing installs): continue a waiting install. */
    suspend fun onAppResumed() {}
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
    fun install() = viewModelScope.launch { _dismissed.value = null; updates.downloadAndInstall() }
    fun later(version: String) { _dismissed.value = version }
}

/** Checks once when the app opens and offers a newer version right away (like Nuvio). */
@Composable
fun UpdatePrompt(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dismissed by vm.dismissed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.checkOnStart() }
    val version = when (val s = state) {
        is UpdateState.Available -> s.version
        is UpdateState.Downloading -> s.version
        is UpdateState.NeedsPermission -> s.version
        is UpdateState.Installing -> s.version
        is UpdateState.Failed -> "failed"
        else -> null
    }
    if (version == null || version == dismissed) return
    UpdateDialog(state, vm.installedVersion, onUpdate = vm::install, onLater = { vm.later(version) })
}

@Composable
fun UpdateDialog(state: UpdateState, installed: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    AlertDialog(
        onDismissRequest = { if (!busy) onLater() },
        modifier = Modifier.testTag("update-dialog"),
        title = {
            Text(
                when (state) {
                    is UpdateState.Available -> stringResource(R.string.update_title, state.version)
                    is UpdateState.Downloading -> stringResource(R.string.update_title, state.version)
                    is UpdateState.NeedsPermission -> stringResource(R.string.update_permission_title)
                    is UpdateState.Installing -> stringResource(R.string.update_installing_title)
                    is UpdateState.Failed -> stringResource(R.string.update_failed_title)
                    else -> ""
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is UpdateState.Available -> {
                        Text(stringResource(R.string.update_message, installed), style = MaterialTheme.typography.bodyMedium)
                        if (state.sizeBytes > 0) Text(stringResource(R.string.update_size, state.sizeBytes / (1024 * 1024)), style = MaterialTheme.typography.bodySmall)
                        state.notes.takeIf { it.isNotBlank() }?.let { notes ->
                            Text(notes.take(2000), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                        }
                    }
                    is UpdateState.Downloading -> {
                        val f = state.fraction
                        Text(if (f != null) stringResource(R.string.update_downloading_percent, (f * 100).toInt()) else stringResource(R.string.update_downloading))
                        if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is UpdateState.NeedsPermission -> Text(stringResource(R.string.update_permission_message))
                    is UpdateState.Installing -> Text(stringResource(R.string.update_installing_message))
                    is UpdateState.Failed -> Text(state.message)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> Button(onClick = onUpdate, modifier = Modifier.testTag("update-now")) { Text(stringResource(R.string.update_now)) }
                is UpdateState.NeedsPermission -> Button(onClick = onUpdate) { Text(stringResource(R.string.update_allow)) }
                is UpdateState.Failed -> Button(onClick = onUpdate) { Text(stringResource(R.string.update_retry)) }
                else -> Unit
            }
        },
        dismissButton = {
            if (!busy) TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
        },
    )
}

/** One line for Settings: "Version 0.4.0 · Up to date" / "Update to 0.5.0" / "Downloading 40 %". */
@Composable
fun updateSummary(installed: String, state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.update_tap_to_check, installed)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, installed)
    is UpdateState.Available -> stringResource(R.string.update_available, state.version)
    is UpdateState.Downloading -> stringResource(R.string.update_downloading_percent, ((state.fraction ?: 0f) * 100).toInt())
    is UpdateState.NeedsPermission -> stringResource(R.string.update_permission_title)
    is UpdateState.Installing -> stringResource(R.string.update_installing_title)
    is UpdateState.Failed -> stringResource(R.string.update_failed, state.message)
}
