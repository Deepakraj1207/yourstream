package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.catalog.api.CatalogProviderInfo
import dev.yourstream.catalog.sync.AddonSource
import dev.yourstream.catalog.sync.CatalogAddon
import dev.yourstream.catalog.sync.CatalogAddons
import dev.yourstream.core.common.AppResult
import dev.yourstream.ui.core.Artwork
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.inkPanel
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What an add-on's manifest says about it, for its card. */
data class AddonDetails(
    val version: String? = null,
    val logo: String? = null,
    val description: String? = null,
    /** "movie", "series", … */
    val types: List<String> = emptyList(),
    /** "stream", "catalog", "meta", … */
    val resources: List<String> = emptyList(),
    /** How many id prefixes it names ("id rules"). */
    val idRules: Int = 0,
    val configurable: Boolean = false,
    val p2p: Boolean = false,
) {
    companion object {
        fun of(info: CatalogProviderInfo) = AddonDetails(
            info.version, info.logo, info.description?.takeIf { it.isNotBlank() }, info.types, info.resources,
            info.idPrefixes.size, info.configurable, info.p2p,
        )
    }
}

/** An add-on card: what it is, and what its manifest said (null while asking). */
data class AddonRow(
    val addon: CatalogAddon,
    val catalogs: Int? = null,
    /** Null while asking, then whether its manifest answered. */
    val reachable: Boolean? = null,
    val streams: Boolean = false,
    val details: Boolean = false,
    val info: AddonDetails? = null,
    /** Its manifest is being fetched again (its refresh button, or refresh all). */
    val refreshing: Boolean = false,
) {
    /** Disabled / Refreshing / Active / Unavailable, as the card's first chip says. */
    val status: AddonStatus get() = when {
        !addon.enabled -> AddonStatus.DISABLED
        refreshing || reachable == null -> AddonStatus.REFRESHING
        reachable == false -> AddonStatus.UNAVAILABLE
        else -> AddonStatus.ACTIVE
    }
}

enum class AddonStatus { DISABLED, REFRESHING, ACTIVE, UNAVAILABLE }

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
    val active: Int get() = addons.count { it.addon.enabled }
    /** Catalogs of the add-ons that are on. */
    val catalogs: Int get() = addons.filter { it.addon.enabled }.sumOf { it.catalogs ?: 0 }
}

/** How many add-ons answered the last refresh. */
data class RefreshResult(val answered: Int, val total: Int)

sealed interface AddonsAction {
    data class Add(val link: String, val source: AddonSource = AddonSource.STREMIO) : AddonsAction
    data class SetEnabled(val id: String, val enabled: Boolean) : AddonsAction
    data class Remove(val id: String) : AddonsAction
    /** Fetch every add-on's manifest again: new names, catalogs and stream support. */
    data object Refresh : AddonsAction
    /** Fetch one add-on's manifest again (its card's refresh button). */
    data class RefreshOne(val id: String) : AddonsAction
    /** One place up (-1) or down (+1): Home's rows and the stream groups follow this order. */
    data class Move(val id: String, val by: Int) : AddonsAction
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
            is AddonsAction.RefreshOne -> refreshOne(action.id)
            is AddonsAction.Move -> store.move(action.id, action.by)
        }
    }

    /**
     * The refresh button: asks every add-on for its manifest again, all at once. Each card shows
     * "Refreshing" until its add-on answers; the page then says how many answered.
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

    private fun refreshOne(id: String) {
        val a = store.addons.value.firstOrNull { it.id == id } ?: return
        if (status.value[id]?.refreshing == true) return
        status.value = status.value + (id to (status.value[id] ?: AddonRow(a)).copy(refreshing = true))
        viewModelScope.launch {
            val info = runCatching { store.refresh(id) }.getOrNull()
            // Not reachable now: keep what the card showed before, marked unavailable.
            status.value = status.value + (id to (info?.let { row(a, it) } ?: (status.value[id] ?: AddonRow(a)).copy(reachable = false, refreshing = false)))
        }
    }

    private fun row(a: CatalogAddon, info: CatalogProviderInfo?) =
        AddonRow(a, info?.catalogs?.count { !it.requiresExtra }, info != null, info?.supportsStreams == true, info?.supportsMeta == true,
            info?.let(AddonDetails::of))

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

/**
 * The Add-ons page, laid out like Nuvio's: an overview (add-ons, active, catalogs), the install
 * box, then a card per add-on in the user's order: logo, name and version, an on/off switch, and
 * buttons to move it up or down, refresh it, open its settings page (configurable add-ons) and
 * delete it; chips for its status and what it offers, its description, and a summary line
 * ("Movie / Series • stream, catalog, meta • 1 id rules").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(state: AddonsUiState, onAction: (AddonsAction) -> Unit, onBack: () -> Unit) {
    var deleting by remember { mutableStateOf<CatalogAddon?>(null) }
    val uri = LocalUriHandler.current
    ScreenScaffold(
        stringResource(R.string.settings_addons), onBack = onBack,
        actions = { RefreshButton(state.refreshing) { onAction(AddonsAction.Refresh) } },
    ) {
        // Pull down to refresh on phones and tablets; the top-bar button does the same (and on TV).
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { onAction(AddonsAction.Refresh) }) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).testTag("addons"),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Padded { Overview(state) } }
                item { Padded { AddBox(state, onAction) } }
                item {
                    Text(
                        stringResource(R.string.addons_installed), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = LocalDimens.current.gutter, top = 4.dp).semantics { heading() },
                    )
                }
                if (state.addons.isEmpty()) item { Intro(R.string.addons_none) }
                itemsIndexed(state.addons, key = { _, r -> r.addon.id }) { i, row ->
                    Padded {
                        AddonCard(
                            row, first = i == 0, last = i == state.addons.lastIndex, onAction = onAction,
                            onDelete = { deleting = row.addon },
                            onConfigure = row.addon.manifestUrl?.takeIf { row.info?.configurable == true }?.let { url ->
                                { runCatching { uri.openUri(CatalogAddons.configureUrl(url)) } }
                            },
                        )
                    }
                }
            }
        }
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.addons_delete_title)) },
            text = { Text(stringResource(R.string.addons_delete_message, a.name)) },
            confirmButton = {
                TextButton(onClick = { onAction(AddonsAction.Remove(a.id)); deleting = null }, modifier = Modifier.testTag("addon-delete-yes")) {
                    Text(stringResource(R.string.addons_delete_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.addons_delete_no)) } },
            modifier = Modifier.testTag("addon-delete-dialog"),
        )
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

private val CardShape = RoundedCornerShape(28.dp)

/** Add-ons · Active · Catalogs. */
@Composable
private fun Overview(state: AddonsUiState) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().inkPanel(CardShape).testTag("addons-overview")) {
        Row(Modifier.padding(vertical = 18.dp)) {
            Stat(state.addons.size, stringResource(R.string.addons_stat_addons), Modifier.weight(1f))
            Stat(state.active, stringResource(R.string.addons_stat_active), Modifier.weight(1f))
            Stat(state.catalogs, stringResource(R.string.addons_stat_catalogs), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddBox(state: AddonsUiState, onAction: (AddonsAction) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.added) { if (state.added != null) link = "" }
    val submit = { if (link.isNotBlank() && !state.adding) onAction(AddonsAction.Add(link)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = link, onValueChange = { link = it }, singleLine = true, shape = MaterialTheme.shapes.large,
            label = { Text(stringResource(R.string.addons_add_label)) },
            placeholder = { Text(stringResource(R.string.addons_add_hint)) },
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("addon-link"),
        )
        Button(onClick = submit, enabled = link.isNotBlank() && !state.adding, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().focusHighlight().testTag("addon-add")) {
            if (state.adding) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.addons_checking_addon), Modifier.padding(start = 10.dp))
            } else Text(stringResource(R.string.addons_add))
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
private fun AddonCard(row: AddonRow, first: Boolean, last: Boolean, onAction: (AddonsAction) -> Unit, onDelete: () -> Unit, onConfigure: (() -> Unit)?) {
    val a = row.addon
    val c = MaterialTheme.colorScheme
    Surface(shape = CardShape, color = c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp).inkPanel(CardShape).testTag("addon:${a.id}")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Logo(row)
                Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = row.info?.version?.let { stringResource(R.string.addons_version, it) }
                        ?: if (a.builtIn) stringResource(R.string.addons_built_in) else a.manifestUrl?.substringAfter("://")?.substringBefore('/')
                    sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Switch(checked = a.enabled, onCheckedChange = { onAction(AddonsAction.SetEnabled(a.id, it)) },
                    modifier = Modifier.focusHighlight().testTag("addon-toggle:${a.id}"))
            }
            // Move up · move down · refresh · settings · delete (right-aligned, like Nuvio's).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!first) Action(Icons.Filled.KeyboardArrowUp, stringResource(R.string.addons_move_up, a.name), "addon-up:${a.id}") { onAction(AddonsAction.Move(a.id, -1)) }
                if (!last) Action(Icons.Filled.KeyboardArrowDown, stringResource(R.string.addons_move_down, a.name), "addon-down:${a.id}") { onAction(AddonsAction.Move(a.id, 1)) }
                if (row.refreshing) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                    Action(Icons.Filled.Refresh, stringResource(R.string.addons_refresh_one, a.name), "addon-refresh:${a.id}") { onAction(AddonsAction.RefreshOne(a.id)) }
                }
                onConfigure?.let { Action(Icons.Filled.Settings, stringResource(R.string.addons_configure, a.name), "addon-configure:${a.id}", tint = c.primary, onClick = it) }
                if (!a.builtIn) Action(Icons.Filled.Delete, stringResource(R.string.addons_remove, a.name), "addon-remove:${a.id}", tint = c.error, onClick = onDelete)
            }
            HorizontalDivider(color = c.outlineVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(when (row.status) {
                    AddonStatus.DISABLED -> R.string.addons_status_disabled
                    AddonStatus.REFRESHING -> R.string.addons_status_refreshing
                    AddonStatus.ACTIVE -> R.string.addons_status_active
                    AddonStatus.UNAVAILABLE -> R.string.addons_status_unavailable
                }), error = row.status == AddonStatus.UNAVAILABLE && a.enabled, tag = "addon-status:${a.id}")
                row.info?.resources?.size?.takeIf { it > 0 }?.let { Chip(pluralStringResource(R.plurals.addons_resources, it, it)) }
                row.catalogs?.takeIf { it > 0 }?.let { Chip(pluralStringResource(R.plurals.addons_catalogs, it, it)) }
                if (row.info?.configurable == true) Chip(stringResource(R.string.addons_configurable))
            }
            row.info?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            row.info?.let { summary(it) }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.testTag("addon-summary:${a.id}"))
            }
        }
    }
}

/** "Movie / Series • stream, catalog, meta • 1 id rules • P2P" (pure; tested). */
@Composable
private fun summary(d: AddonDetails): String? = addonSummary(d, pluralStringResource(R.plurals.addons_id_rules, d.idRules, d.idRules))

internal fun addonSummary(d: AddonDetails, idRules: String): String? = listOfNotNull(
    d.types.joinToString(" / ") { t -> t.replaceFirstChar { it.titlecase(Locale.ROOT) } }.ifEmpty { null },
    d.resources.joinToString(", ").ifEmpty { null },
    idRules.takeIf { d.idRules > 0 },
    "P2P".takeIf { d.p2p },
).joinToString(" • ").ifEmpty { null }

@Composable
private fun Logo(row: AddonRow) {
    val shape = RoundedCornerShape(18.dp)
    val logo = row.info?.logo
    if (logo != null) {
        Artwork(logo, row.addon.name, Modifier.size(64.dp).clip(shape))
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(PuzzlePiece, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, tag: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.focusHighlight().testTag(tag)) { Icon(icon, contentDescription = label, tint = tint) }
}

@Composable
private fun Chip(text: String, error: Boolean = false, tag: String? = null) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (error) c.errorContainer else c.surfaceContainerHighest,
        contentColor = if (error) c.onErrorContainer else c.onSurfaceVariant,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
}

/** A plain puzzle piece: the card's picture for an add-on without a logo. */
private val PuzzlePiece: ImageVector = ImageVector.Builder("PuzzlePiece", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(androidx.compose.ui.graphics.Color.Black)) {
    // A square body with a knob on the top and on the right.
    moveTo(4f, 7f); lineTo(9f, 7f)
    arcToRelative(2.5f, 2.5f, 0f, true, true, 5f, 0f)
    lineTo(18f, 7f); lineTo(18f, 11f)
    arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, 5f)
    lineTo(18f, 20f); lineTo(4f, 20f); close()
}.build()
