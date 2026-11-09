package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.telegram.api.ApiCredentials
import dev.yourstream.telegram.api.CredentialsSource
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramCredentialStore
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

data class TelegramApiUiState(
    val source: CredentialsSource = CredentialsSource.NONE,
    val activeApiId: Int? = null,
    val hasBuiltIn: Boolean = false,
    /** Changing credentials while signed in starts a new Telegram session (sign in again). */
    val signedIn: Boolean = false,
)

sealed interface TelegramApiAction {
    data class Save(val credentials: ApiCredentials) : TelegramApiAction
    data object UseBuiltIn : TelegramApiAction
}

@HiltViewModel
class TelegramApiViewModel @Inject constructor(
    private val store: TelegramCredentialStore,
    auth: TelegramAuthManager,
) : ViewModel() {
    val state: StateFlow<TelegramApiUiState> = auth.state.map { a ->
        TelegramApiUiState(store.activeSource, store.activeApiId, store.hasBuiltIn, signedIn = a == AuthState.Ready)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelegramApiUiState(store.activeSource, store.activeApiId, store.hasBuiltIn))

    /** Stores the change and restarts the app so Telegram starts with it. */
    fun onAction(action: TelegramApiAction) {
        when (action) {
            is TelegramApiAction.Save -> store.save(action.credentials)
            TelegramApiAction.UseBuiltIn -> store.clear()
        }
        store.restartApp()
    }
}

@Composable
fun TelegramApiDestination(onBack: () -> Unit, vm: TelegramApiViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    TelegramApiScreen(state, vm::onAction, onBack)
}

const val MY_TELEGRAM_URL = "https://my.telegram.org/apps"

/**
 * Your own Telegram API ID and hash, as other Telegram clients allow. They identify the app to
 * Telegram (not your account); with your own, the app does not depend on anyone else's. Stored
 * only on this device, the hash encrypted.
 */
@Composable
fun TelegramApiScreen(state: TelegramApiUiState, onAction: (TelegramApiAction) -> Unit, onBack: () -> Unit) {
    // Never kept in saved state: nothing typed here is written anywhere until Save.
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var showHash by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<TelegramApiAction?>(null) }
    val parsed = ApiCredentials.parse(id, hash)
    val uri = LocalUriHandler.current

    fun submit(action: TelegramApiAction) {
        if (state.signedIn) confirm = action else onAction(action)
    }

    ScreenScaffold(stringResource(R.string.api_title), onBack = onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = LocalDimens.current.gutter, vertical = 8.dp).widthIn(max = 640.dp).testTag("telegram-api"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.api_intro), style = MaterialTheme.typography.bodyLarge)
            Text(
                when (state.source) {
                    CredentialsSource.APP -> stringResource(R.string.api_using_app, state.activeApiId ?: 0)
                    CredentialsSource.BUILD -> stringResource(R.string.api_using_built_in, state.activeApiId ?: 0)
                    CredentialsSource.NONE -> stringResource(R.string.api_using_none)
                },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("api-status"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.api_how_title), style = MaterialTheme.typography.titleSmall)
                listOf(R.string.api_how_1, R.string.api_how_2, R.string.api_how_3).forEachIndexed { i, s ->
                    Text("${i + 1}. " + stringResource(s), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { runCatching { uri.openUri(MY_TELEGRAM_URL) } }, modifier = Modifier.focusHighlight().testTag("open-my-telegram")) {
                    Text(stringResource(R.string.api_open_site))
                }
            }
            OutlinedTextField(
                id, { id = it.filter(Char::isDigit).take(12) }, singleLine = true,
                label = { Text(stringResource(R.string.api_id)) },
                isError = id.isNotEmpty() && !parsed.idValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("api-id"),
            )
            OutlinedTextField(
                hash, { hash = it.trim().take(32) }, singleLine = true,
                label = { Text(stringResource(R.string.api_hash)) },
                supportingText = { Text(stringResource(if (hash.isNotEmpty() && !parsed.hashValid) R.string.api_hash_invalid else R.string.api_hash_help)) },
                isError = hash.isNotEmpty() && !parsed.hashValid,
                visualTransformation = if (showHash) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showHash = !showHash }, modifier = Modifier.focusHighlight()) {
                        Text(stringResource(if (showHash) R.string.api_hide else R.string.api_show))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("api-hash"),
            )
            if (state.signedIn) Text(stringResource(R.string.api_signed_in_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { parsed.credentials?.let { submit(TelegramApiAction.Save(it)) } },
                    enabled = parsed.credentials != null,
                    modifier = Modifier.focusHighlight().testTag("api-save"),
                ) { Text(stringResource(R.string.api_save)) }
                if (state.source == CredentialsSource.APP) {
                    OutlinedButton(onClick = { submit(TelegramApiAction.UseBuiltIn) }, modifier = Modifier.focusHighlight().testTag("api-remove")) {
                        Text(stringResource(if (state.hasBuiltIn) R.string.api_use_built_in else R.string.api_remove))
                    }
                }
            }
            Text(stringResource(R.string.api_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.api_confirm_title)) },
            text = { Text(stringResource(R.string.api_confirm_message)) },
            confirmButton = { TextButton(onClick = { confirm = null; onAction(action) }, modifier = Modifier.testTag("api-confirm")) { Text(stringResource(R.string.api_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.player_cancel)) } },
        )
    }
}
