package dev.yourstream.ui.screens.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.sync.ProgressSync
import dev.yourstream.sync.SyncProblem
import dev.yourstream.sync.SyncStatus
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(val status: SyncStatus = SyncStatus(), val busy: Boolean = false)

sealed interface SyncAction {
    /** The passphrase is passed as characters and cleared after use; it is never stored or saved. */
    class TurnOn(val passphrase: CharArray) : SyncAction
    data object SyncNow : SyncAction
    data class TurnOff(val deleteShared: Boolean) : SyncAction
}

@HiltViewModel
class ProgressSyncViewModel @Inject constructor(private val sync: ProgressSync) : ViewModel() {
    private val busy = MutableStateFlow(false)
    val state: StateFlow<SyncUiState> = combine(sync.status, busy) { s, b -> SyncUiState(s, b || s.running) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState(sync.status.value))

    fun onAction(action: SyncAction) {
        busy.value = true
        viewModelScope.launch {
            try {
                when (action) {
                    is SyncAction.TurnOn -> sync.enable(action.passphrase)
                    SyncAction.SyncNow -> sync.syncNow()
                    is SyncAction.TurnOff -> sync.disable(action.deleteShared)
                }
            } finally { busy.value = false }
        }
    }
}

@Composable
fun ProgressSyncDestination(onBack: () -> Unit, vm: ProgressSyncViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ProgressSyncScreen(state, vm::onAction, onBack, now = System.currentTimeMillis())
}

/** Minimum passphrase length: the key is only as strong as the passphrase. */
const val MIN_PASSPHRASE = 8

/**
 * Optional sync of watch progress across the user's devices. Off by default. States plainly what
 * is shared, where, and how it is protected, before anything is turned on.
 */
@Composable
fun ProgressSyncScreen(state: SyncUiState, onAction: (SyncAction) -> Unit, onBack: () -> Unit, now: Long) {
    val s = state.status
    var confirmOff by remember { mutableStateOf(false) }
    ScreenScaffold(stringResource(R.string.sync_title), onBack = onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = LocalDimens.current.gutter, vertical = 8.dp).widthIn(max = 720.dp).testTag("sync"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyLarge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.sync_point_where, R.string.sync_point_encrypted, R.string.sync_point_what, R.string.sync_point_same).forEach {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            s.problem?.let { p ->
                Text(
                    stringResource(when (p) {
                        SyncProblem.WRONG_PASSPHRASE -> R.string.sync_problem_passphrase
                        SyncProblem.NOT_SIGNED_IN -> R.string.sync_problem_signed_out
                        SyncProblem.NETWORK -> R.string.sync_problem_network
                        SyncProblem.FAILED -> R.string.sync_problem_failed
                    }),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("sync-problem"),
                )
            }
            if (!s.enabled) TurnOn(state.busy, onAction) else On(state, now, onAction) { confirmOff = true }
        }
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text(stringResource(R.string.sync_off_title)) },
        text = { Text(stringResource(R.string.sync_off_message)) },
        confirmButton = { TextButton(onClick = { confirmOff = false; onAction(SyncAction.TurnOff(deleteShared = true)) }, modifier = Modifier.testTag("sync-off-delete")) { Text(stringResource(R.string.sync_off_delete)) } },
        dismissButton = { TextButton(onClick = { confirmOff = false; onAction(SyncAction.TurnOff(deleteShared = false)) }, modifier = Modifier.testTag("sync-off-keep")) { Text(stringResource(R.string.sync_off_keep)) } },
    )
}

@Composable
private fun TurnOn(busy: Boolean, onAction: (SyncAction) -> Unit) {
    // Not saved across process death on purpose: a passphrase is never written anywhere.
    var pass by remember { mutableStateOf("") }
    val ok = pass.length >= MIN_PASSPHRASE
    OutlinedTextField(
        pass, { pass = it }, singleLine = true,
        label = { Text(stringResource(R.string.sync_passphrase)) },
        supportingText = { Text(stringResource(R.string.sync_passphrase_help, MIN_PASSPHRASE)) },
        leadingIcon = { Icon(Icons.Filled.Lock, null) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("sync-passphrase"),
    )
    Button(onClick = { onAction(SyncAction.TurnOn(pass.toCharArray())); pass = "" }, enabled = ok && !busy, modifier = Modifier.focusHighlight().testTag("sync-on")) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.sync_turn_on))
    }
}

@Composable
private fun On(state: SyncUiState, now: Long, onAction: (SyncAction) -> Unit, onTurnOff: () -> Unit) {
    val s = state.status
    Column(Modifier.semantics(mergeDescendants = true) {}.testTag("sync-status"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sync_is_on), style = MaterialTheme.typography.titleMedium)
        Text(
            s.lastSyncAt?.let { at ->
                val ago = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
                if (s.lastFrom != null) stringResource(R.string.sync_last_from, ago, s.lastFrom!!) else stringResource(R.string.sync_last, ago)
            } ?: stringResource(R.string.sync_never),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (s.lastSyncAt != null) Text(stringResource(R.string.sync_counts, s.lastPulled, s.shared), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { onAction(SyncAction.SyncNow) }, enabled = !state.busy, modifier = Modifier.focusHighlight().testTag("sync-now")) { Text(stringResource(R.string.sync_now)) }
        TextButton(onClick = onTurnOff, enabled = !state.busy, modifier = Modifier.focusHighlight().testTag("sync-off")) { Text(stringResource(R.string.sync_turn_off)) }
        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
