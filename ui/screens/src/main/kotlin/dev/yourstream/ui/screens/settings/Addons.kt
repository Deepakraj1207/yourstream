package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.catalog.sync.CatalogAddon
import dev.yourstream.catalog.sync.CatalogAddons
import dev.yourstream.core.common.AppResult
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.inkPanel
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An add-on row: what it is, and what its manifest said (null while checking). */
data class AddonRow(val addon: CatalogAddon, val catalogs: Int? = null, val reachable: Boolean? = null, val streams: Boolean = false)

data class AddonsUiState(
    val addons: List<AddonRow> = emptyList(),
    val adding: Boolean = false,
    /** Why the last add failed, as the add-on or network said. */
    val error: String? = null,
    /** Name of the add-on just added (confirmation). */
    val added: String? = null,
)

sealed interface AddonsAction {
    data class Add(val link: String) : AddonsAction
    data class SetEnabled(val id: String, val enabled: Boolean) : AddonsAction
    data class Remove(val id: String) : AddonsAction
}

@HiltViewModel
class AddonsViewModel @Inject constructor(private val store: CatalogAddons) : ViewModel() {
    private val status = MutableStateFlow<Map<String, AddonRow>>(emptyMap())
    private val ui = MutableStateFlow(AddonsUiState())

    val state: StateFlow<AddonsUiState> = combine(store.addons, status, ui) { list, st, u ->
        u.copy(addons = list.map { a -> st[a.id]?.copy(addon = a) ?: AddonRow(a) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddonsUiState())

    init { refreshStatus() }

    fun onAction(action: AddonsAction) {
        when (action) {
            is AddonsAction.Add -> viewModelScope.launch {
                ui.value = ui.value.copy(adding = true, error = null, added = null)
                ui.value = when (val r = store.add(action.link)) {
                    is AppResult.Ok -> AddonsUiState(added = r.value.name)
                    is AppResult.Err -> AddonsUiState(error = r.error.message)
                }
                refreshStatus()
            }
            is AddonsAction.SetEnabled -> { store.setEnabled(action.id, action.enabled); refreshStatus() }
            is AddonsAction.Remove -> store.remove(action.id)
        }
    }

    /** Asks each enabled add-on what it offers (its manifest). */
    private fun refreshStatus() = viewModelScope.launch {
        store.addons.value.filter { it.enabled && status.value[it.id]?.reachable != true }.forEach { a ->
            launch {
                val info = runCatching { store.describe(a.id) }.getOrNull()
                status.value = status.value + (a.id to AddonRow(a, info?.catalogs?.count { !it.requiresExtra }, info != null, info?.resources?.contains("stream") == true))
            }
        }
    }
}

@Composable
fun AddonsDestination(onBack: () -> Unit, vm: AddonsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    AddonsScreen(state, vm::onAction, onBack)
}

@Composable
fun AddonsScreen(state: AddonsUiState, onAction: (AddonsAction) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.settings_addons), onBack = onBack) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).testTag("addons"),
            contentPadding = PaddingValues(horizontal = LocalDimens.current.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.addons_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { AddBox(state, onAction) }
            item {
                Text(stringResource(R.string.addons_installed), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() })
            }
            items(state.addons, key = { it.addon.id }) { row -> AddonCard(row, onAction) }
        }
    }
}

@Composable
private fun AddBox(state: AddonsUiState, onAction: (AddonsAction) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.added) { if (state.added != null) link = "" }
    val submit = { if (link.isNotBlank() && !state.adding) onAction(AddonsAction.Add(link)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, singleLine = true, shape = MaterialTheme.shapes.medium,
                label = { Text(stringResource(R.string.addons_add_label)) }, placeholder = { Text(stringResource(R.string.addons_add_hint)) },
                isError = state.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).testTag("addon-link"),
            )
            Button(onClick = submit, enabled = link.isNotBlank() && !state.adding, shape = CircleShape, modifier = Modifier.focusHighlight().testTag("addon-add")) {
                if (state.adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.addons_add))
            }
        }
        when {
            state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("addon-error"))
            state.added != null -> Text(stringResource(R.string.addons_added, state.added), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("addon-added"))
            else -> Text(stringResource(R.string.addons_only_catalogs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddonCard(row: AddonRow, onAction: (AddonsAction) -> Unit) {
    val a = row.addon
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp).inkPanel(MaterialTheme.shapes.large).testTag("addon:${a.id}")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val origin = if (a.builtIn) stringResource(R.string.addons_built_in) else a.manifestUrl?.removePrefix("https://")?.substringBefore('/')
                val health = when {
                    !a.enabled -> null
                    row.reachable == false -> stringResource(R.string.addons_unavailable)
                    row.catalogs != null -> listOfNotNull(
                        row.catalogs.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.addons_catalogs, it, it) },
                        if (row.streams) stringResource(R.string.addons_streams) else null,
                    ).joinToString(" · ").ifEmpty { stringResource(R.string.addons_details_only) }
                    else -> stringResource(R.string.addons_checking)
                }
                Text(listOfNotNull(origin, health).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = if (row.reachable == false && a.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (!a.builtIn) {
                IconButton(onClick = { onAction(AddonsAction.Remove(a.id)) }, modifier = Modifier.focusHighlight().testTag("addon-remove:${a.id}")) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.addons_remove, a.name))
                }
            }
            Switch(checked = a.enabled, onCheckedChange = { onAction(AddonsAction.SetEnabled(a.id, it)) }, modifier = Modifier.focusHighlight().testTag("addon-toggle:${a.id}"))
        }
    }
}
