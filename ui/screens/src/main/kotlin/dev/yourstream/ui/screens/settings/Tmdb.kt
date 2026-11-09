package dev.yourstream.ui.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.metadata.sync.SaveTmdbKey
import dev.yourstream.metadata.sync.TmdbKeySource
import dev.yourstream.metadata.sync.TmdbKeys
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

data class TmdbUiState(val source: TmdbKeySource = TmdbKeySource.NONE, val saving: Boolean = false, val error: String? = null, val saved: Boolean = false)

@HiltViewModel
class TmdbViewModel @Inject constructor(private val keys: TmdbKeys, private val save: SaveTmdbKey) : ViewModel() {
    private val ui = MutableStateFlow(TmdbUiState())
    val state: StateFlow<TmdbUiState> = combine(keys.userKey, ui) { _, u -> u.copy(source = keys.source) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TmdbUiState(keys.source))

    fun save(key: String?) = viewModelScope.launch {
        ui.value = TmdbUiState(saving = true)
        ui.value = when (val r = save.invoke(key)) {
            is AppResult.Ok -> TmdbUiState(saved = true)
            is AppResult.Err -> TmdbUiState(error = r.error.message)
        }
    }
}

@Composable
fun TmdbDestination(onBack: () -> Unit, vm: TmdbViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    TmdbScreen(state, onSave = vm::save, onBack = onBack)
}

/** Settings → TMDB: a free key gives better title matching and posters (especially Indian titles). */
@Composable
fun TmdbScreen(state: TmdbUiState, onSave: (String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(stringResource(R.string.tmdb_title), onBack = onBack) {
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = LocalDimens.current.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.tmdb_intro), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    when (state.source) {
                        TmdbKeySource.USER -> R.string.tmdb_using_yours
                        TmdbKeySource.BUILT_IN -> R.string.tmdb_using_built_in
                        TmdbKeySource.NONE -> R.string.tmdb_using_none
                    },
                ),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("tmdb-status"),
            )
            Text(stringResource(R.string.tmdb_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.tmdb_open_site)) }
            OutlinedTextField(
                value = key, onValueChange = { key = it.trim() }, singleLine = true, shape = MaterialTheme.shapes.medium,
                label = { Text(stringResource(R.string.tmdb_key)) },
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.tmdb_hide else R.string.tmdb_show)) } },
                isError = state.error != null,
                supportingText = { Text(state.error ?: stringResource(R.string.tmdb_key_help)) },
                modifier = Modifier.fillMaxWidth().testTag("tmdb-key"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSave(key) }, enabled = key.isNotBlank() && !state.saving, modifier = Modifier.focusHighlight().testTag("tmdb-save")) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.tmdb_save))
                }
                if (state.source == TmdbKeySource.USER) TextButton(onClick = { onSave(null) }, enabled = !state.saving) { Text(stringResource(R.string.tmdb_remove)) }
            }
            if (state.saved) Text(stringResource(R.string.tmdb_saved), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tmdb-saved"))
            Text(stringResource(R.string.tmdb_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
