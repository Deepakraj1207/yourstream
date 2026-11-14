package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.catalog.sync.AddonSource
import dev.yourstream.catalog.sync.CatalogAddon
import dev.yourstream.catalog.sync.CatalogAddons
import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An add-on row: what it is, and what its manifest said (null while checking). */
data class AddonRow(val addon: CatalogAddon, val catalogs: Int? = null, val reachable: Boolean? = null, val streams: Boolean = false, val details: Boolean = false)

/** The Add-ons page's sections. */
enum class AddonsTab { STREMIO, NUVIO }

data class AddonsUiState(
    val addons: List<AddonRow> = emptyList(),
    val adding: Boolean = false,
    /** Why the last add failed, as the add-on or network said. */
    val error: String? = null,
    /** Name of the add-on just added (confirmation). */
    val added: String? = null,
    /** Every add-on's manifest is being fetched again (the refresh button). */
    val refreshing: Boolean = false,
    /** What the last refresh found, until the next action. */
    val refreshed: RefreshResult? = null,
) {
    fun rows(tab: AddonsTab): List<AddonRow> = when (tab) {
        AddonsTab.STREMIO -> addons.filter { it.addon.source != AddonSource.NUVIO }
        AddonsTab.NUVIO -> addons.filter { it.addon.source == AddonSource.NUVIO }
    }
}

/** How many add-ons answered the last refresh. */
data class RefreshResult(val answered: Int, val total: Int)

sealed interface AddonsAction {
    data class Add(val link: String, val source: AddonSource = AddonSource.STREMIO) : AddonsAction
    data class SetEnabled(val id: String, val enabled: Boolean) : AddonsAction
    data class Remove(val id: String) : AddonsAction
    /** Fetch every add-on's manifest again: new names, catalogs and stream support. */
    data object Refresh : AddonsAction
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
                ui.value = when (val r = store.add(action.link, action.source)) {
                    is AppResult.Ok -> AddonsUiState(added = r.value.name)
                    // A Nuvio plugin-repository link gets a short explanation here, like any other error.
                    is AppResult.Err -> AddonsUiState(error = r.error.message)
                }
                refreshStatus()
            }
            is AddonsAction.SetEnabled -> { store.setEnabled(action.id, action.enabled); refreshStatus() }
            is AddonsAction.Remove -> store.remove(action.id)
            AddonsAction.Refresh -> refreshAll()
        }
    }

    /**
     * The refresh button: asks every add-on for its manifest again, all at once. Each card shows
     * "Checking…" until its add-on answers; the page then says how many answered.
     */
    private fun refreshAll() {
        if (ui.value.refreshing) return
        val list = store.addons.value
        ui.value = ui.value.copy(refreshing = true, refreshed = null, error = null, added = null)
        status.value = status.value - list.map { it.id }.toSet()
        viewModelScope.launch {
            val answered = list.map { a ->
                async {
                    val info = runCatching { store.refresh(a.id) }.getOrNull()
                    status.value = status.value + (a.id to row(a, info))
                    info != null
                }
            }.awaitAll().count { it }
            ui.value = ui.value.copy(refreshing = false, refreshed = RefreshResult(answered, list.size))
        }
    }

    private fun row(a: CatalogAddon, info: dev.yourstream.catalog.api.CatalogProviderInfo?) =
        AddonRow(a, info?.catalogs?.count { !it.requiresExtra }, info != null, info?.supportsStreams == true, info?.supportsMeta == true)

    /** Asks each add-on what it offers (its manifest), so every card can say it. */
    private fun refreshStatus() = viewModelScope.launch {
        store.addons.value.filter { status.value[it.id]?.reachable != true }.forEach { a ->
            launch {
                val info = runCatching { store.describe(a.id) }.getOrNull()
                status.value = status.value + (a.id to row(a, info))
            }
        }
    }
}

@Composable
fun AddonsDestination(onBack: () -> Unit, vm: AddonsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    AddonsScreen(state, vm::onAction, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(state: AddonsUiState, onAction: (AddonsAction) -> Unit, onBack: () -> Unit, initialTab: AddonsTab = AddonsTab.STREMIO) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val rows = state.rows(tab)
    ScreenScaffold(
        stringResource(R.string.settings_addons), onBack = onBack,
        actions = { RefreshButton(state.refreshing) { onAction(AddonsAction.Refresh) } },
    ) {
        // Pull down to refresh on phones and tablets; the top-bar button does the same (and on TV).
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { onAction(AddonsAction.Refresh) }) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).testTag("addons"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ChipRow(
                    listOf(
                        ChipOption(AddonsTab.STREMIO, stringResource(R.string.addons_tab_stremio, state.rows(AddonsTab.STREMIO).size)),
                        ChipOption(AddonsTab.NUVIO, stringResource(R.string.addons_tab_nuvio, state.rows(AddonsTab.NUVIO).size)),
                    ),
                    tab, { tab = it }, Modifier.testTag("addons-tabs"),
                )
            }
            item { Intro(if (tab == AddonsTab.NUVIO) R.string.addons_intro_nuvio else R.string.addons_intro) }
            item { Padded { AddBox(state, if (tab == AddonsTab.NUVIO) AddonSource.NUVIO else AddonSource.STREMIO, onAction) } }
            item {
                Text(
                    stringResource(R.string.addons_installed), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = LocalDimens.current.gutter, top = 12.dp).semantics { heading() },
                )
            }
            if (rows.isEmpty()) item { Intro(if (tab == AddonsTab.NUVIO) R.string.addons_none_nuvio else R.string.addons_none) }
            items(rows, key = { it.addon.id }) { row -> Padded { AddonCard(row, onAction) } }
        }
        }
    }
}

/** The top bar's refresh: a spinner in its place while the add-ons are asked. */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.addons_refresh)
    IconButton(onClick = onClick, enabled = !refreshing, modifier = Modifier.focusHighlight().testTag("addons-refresh")) {
        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp).testTag("addons-refreshing"), strokeWidth = 2.dp)
        else Icon(Icons.Filled.Refresh, contentDescription = label)
    }
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = LocalDimens.current.gutter)) { content() }
}

@Composable
private fun Intro(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LocalDimens.current.gutter))
}

@Composable
private fun AddBox(state: AddonsUiState, source: AddonSource, onAction: (AddonsAction) -> Unit) {
    var link by rememberSaveable(source) { mutableStateOf("") }
    LaunchedEffect(state.added) { if (state.added != null) link = "" }
    val submit = { if (link.isNotBlank() && !state.adding) onAction(AddonsAction.Add(link, source)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, singleLine = true, shape = MaterialTheme.shapes.medium,
                label = { Text(stringResource(if (source == AddonSource.NUVIO) R.string.addons_add_label_nuvio else R.string.addons_add_label)) },
                placeholder = { Text(stringResource(R.string.addons_add_hint)) },
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
            state.refreshed != null -> Text(
                if (state.refreshed.answered == state.refreshed.total) pluralStringResource(R.plurals.addons_refreshed_all, state.refreshed.total, state.refreshed.total)
                else stringResource(R.string.addons_refreshed_some, state.refreshed.answered, state.refreshed.total),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("addons-refreshed"),
            )
            state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("addon-error"))
            state.added != null -> Text(stringResource(R.string.addons_added, state.added), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("addon-added"))
            else -> Text(stringResource(R.string.addons_what_works), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonCard(row: AddonRow, onAction: (AddonsAction) -> Unit) {
    val a = row.addon
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp).inkPanel(MaterialTheme.shapes.large).testTag("addon:${a.id}")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val origin = if (a.builtIn) stringResource(R.string.addons_built_in)
                    else a.manifestUrl?.substringAfter("://")?.substringBefore('/')
                Text(origin.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        row.reachable == false -> Badge(stringResource(R.string.addons_unavailable), error = a.enabled)
                        row.reachable == null -> Badge(stringResource(R.string.addons_checking))
                        else -> {
                            if (row.streams) Badge(stringResource(R.string.addons_badge_streams), strong = true)
                            row.catalogs?.takeIf { it > 0 }?.let { Badge(pluralStringResource(R.plurals.addons_catalogs, it, it)) }
                            if (row.details) Badge(stringResource(R.string.addons_badge_details))
                        }
                    }
                }
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

@Composable
private fun Badge(text: String, strong: Boolean = false, error: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when { error -> c.errorContainer; strong -> c.secondaryContainer; else -> c.surfaceVariant },
        contentColor = when { error -> c.onErrorContainer; strong -> c.onSecondaryContainer; else -> c.onSurfaceVariant },
    ) { Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
}

