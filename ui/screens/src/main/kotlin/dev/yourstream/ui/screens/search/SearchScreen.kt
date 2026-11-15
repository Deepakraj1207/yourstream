package dev.yourstream.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.LocalBottomClearance
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.MediaCard
import dev.yourstream.ui.core.MediaRow
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.SkeletonCard
import dev.yourstream.ui.core.SkeletonRow
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.tileCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun SearchRoute(onOpen: (OpenTarget) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SearchScreen(state, vm::onAction, onOpen)
}

/**
 * The Search tab, laid out like Nuvio's: the search field, then recent searches while the field
 * is focused and empty, then Discover (type, catalog and genre drop-downs over a poster grid).
 * While typing: the user's own Telegram titles first, then one row per add-on and type. The
 * heading reads "Discover" once the empty page is scrolled. Requests ("unwatched Tamil movies")
 * still get Media Brain's answer above the rows.
 */
@Composable
fun SearchScreen(state: SearchUiState, onAction: (SearchAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val blank = state.query.isBlank()
    val discovering by remember(blank) { derivedStateOf { blank && list.firstVisibleItemIndex > 0 } }
    var focused by remember { mutableStateOf(false) }
    // Opening a result means the search was meant: it joins the recent searches.
    val open: (OpenTarget) -> Unit = { if (!blank) onAction(SearchAction.Submit); onOpen(it) }
    ScreenScaffold(stringResource(if (discovering) R.string.search_discover else R.string.nav_search)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val d = LocalDimens.current
            val columns = ((maxWidth - d.gutter * 2 + d.cardGap) / (d.posterWidth + d.cardGap)).toInt().coerceAtLeast(2)
            LazyColumn(
                Modifier.fillMaxSize().testTag("search"), state = list,
                contentPadding = PaddingValues(bottom = LocalBottomClearance.current + 16.dp),
            ) {
                item(key = "field") {
                    SearchField(state.query, onAction, Modifier.onFocusChanged { focused = it.isFocused }) { keyboard?.hide(); onAction(SearchAction.Submit) }
                }
                if (blank) {
                    if (focused && state.recent.isNotEmpty()) recent(state.recent, onAction)
                    discover(state.discover, columns, onAction, open)
                } else {
                    results(state, onAction, open)
                }
            }
            if (blank) LoadMoreWhenNearEnd(list, state.discover, onAction)
        }
    }
}

/** Nuvio's field: a filled, 16 dp rounded box; the clear button only once something is typed. */
@Composable
private fun SearchField(query: String, onAction: (SearchAction) -> Unit, modifier: Modifier, onSearch: () -> Unit) {
    val d = LocalDimens.current
    val shape = RoundedCornerShape(16.dp)
    TextField(
        value = query,
        onValueChange = { onAction(SearchAction.QueryChanged(it)) },
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        singleLine = true,
        shape = shape,
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onAction(SearchAction.Clear) }, modifier = Modifier.testTag("search-clear")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
            }
        },
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.padding(start = d.gutter, end = d.gutter, top = 6.dp, bottom = 14.dp).widthIn(max = 720.dp).fillMaxWidth()
            .focusHighlight(shape).testTag("search-field"),
    )
}

private fun LazyListScope.recent(recent: List<String>, onAction: (SearchAction) -> Unit) {
    item(key = "recent-title") {
        Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = LocalDimens.current.gutter, vertical = 4.dp).semantics { heading() })
    }
    recent.forEach { q ->
        item(key = "recent:$q") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onAction(SearchAction.PickRecent(q)) }.focusHighlight()
                    .padding(start = LocalDimens.current.gutter, end = LocalDimens.current.gutter - 12.dp).testTag("recent:$q"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { onAction(SearchAction.RemoveRecent(q)) }, modifier = Modifier.testTag("recent-remove:$q")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_remove_recent))
                }
            }
        }
    }
    item(key = "recent-gap") { Spacer(Modifier.height(14.dp)) }
}

private fun LazyListScope.discover(d: DiscoverUiState, columns: Int, onAction: (SearchAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    item(key = "discover-title") {
        Text(stringResource(R.string.search_discover), style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(horizontal = LocalDimens.current.gutter).semantics { heading() }.testTag("discover"))
    }
    if (!d.loadingCatalogs && d.catalogs.isEmpty()) {
        item(key = "discover-none") { Notice(R.string.discover_no_catalogs_title, R.string.discover_no_catalogs_message, "discover-empty") }
        return
    }
    item(key = "discover-chips") { DiscoverChips(d, onAction) }
    d.catalog?.let { c ->
        item(key = "discover-context") {
            Text(stringResource(R.string.search_catalog_context, c.addon, typeLabel(c.type)), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LocalDimens.current.gutter, vertical = 4.dp).testTag("discover-context"))
        }
    }
    when {
        d.loadingCatalogs || d.loading -> repeat(2) { i -> item(key = "discover-skeleton:$i") { SkeletonGridRow(columns) } }
        d.failed -> item(key = "discover-failed") {
            Notice(R.string.discover_failed_title, R.string.discover_failed_message, "discover-failed") {
                d.catalog?.let { onAction(SearchAction.DiscoverCatalog(it.key)) }
            }
        }
        d.tiles.isEmpty() -> item(key = "discover-empty") { Notice(R.string.discover_empty_title, R.string.discover_empty_message, "discover-empty") }
        else -> {
            d.tiles.chunked(columns).forEachIndexed { i, row ->
                item(key = "discover-row:$i:${row.first().card.key}") { GridRow(row, columns, onOpen) }
            }
            if (d.loadingMore) item(key = "discover-more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            }
        }
    }
}

/** Type, catalog and genre, each a drop-down chip (Nuvio's row of three). */
@Composable
private fun DiscoverChips(d: DiscoverUiState, onAction: (SearchAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = LocalDimens.current.gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DropdownChip(d.type?.let { typeLabel(it) } ?: stringResource(R.string.search_select_type), "discover-type",
            d.types.map { it.name to typeLabel(it) }, enabled = d.types.size > 1) { n -> onAction(SearchAction.DiscoverType(CatalogType.valueOf(n))) }
        DropdownChip(d.catalog?.name ?: stringResource(R.string.search_select_catalog), "discover-catalog",
            d.catalogsOfType.map { it.key to it.name }, enabled = d.catalogsOfType.size > 1) { onAction(SearchAction.DiscoverCatalog(it)) }
        val c = d.catalog
        if (c != null && c.genres.isNotEmpty()) {
            val all = stringResource(R.string.search_all_genres)
            val options = (if (c.genreRequired) emptyList() else listOf(ALL to all)) + c.genres.map { it to it }
            DropdownChip(d.genre ?: all, "discover-genre", options, enabled = true) { onAction(SearchAction.DiscoverGenre(it.takeIf { g -> g != ALL })) }
        }
    }
}

@Composable
private fun DropdownChip(label: String, tag: String, options: List<Pair<String, String>>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false, onClick = { open = true }, enabled = enabled, label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.heightIn(min = 40.dp).focusHighlight(RoundedCornerShape(16.dp)).testTag(tag),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) }, modifier = Modifier.testTag("$tag:$id"))
            }
        }
    }
}

@Composable
private fun GridRow(row: List<Tile>, columns: Int, onOpen: (OpenTarget) -> Unit) {
    val d = LocalDimens.current
    val card = tileCard()
    Row(Modifier.fillMaxWidth().padding(horizontal = d.gutter, vertical = d.rowGap / 2), horizontalArrangement = Arrangement.spacedBy(d.cardGap)) {
        row.forEach { t -> MediaCard(card(t), { onOpen(t.open) }, Modifier.weight(1f).testTag("discover-tile")) }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun SkeletonGridRow(columns: Int) {
    val d = LocalDimens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = d.gutter, vertical = d.rowGap / 2).testTag("discover-loading"), horizontalArrangement = Arrangement.spacedBy(d.cardGap)) {
        repeat(columns) { SkeletonCard(modifier = Modifier.weight(1f)) }
    }
}

/** Discover loads its next page once the last rows come into view. */
@Composable
private fun LoadMoreWhenNearEnd(list: androidx.compose.foundation.lazy.LazyListState, d: DiscoverUiState, onAction: (SearchAction) -> Unit) {
    val more = d.nextSkip != null && !d.loading && !d.loadingMore
    LaunchedEffect(list, more) {
        if (!more) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 2 } }
            .distinctUntilChanged().filter { it }.collect { onAction(SearchAction.DiscoverMore) }
    }
}

private fun LazyListScope.results(state: SearchUiState, onAction: (SearchAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    state.brain?.let { b -> item(key = "brain") { Brain(b, onOpen) } }
    if (state.suggestions.isNotEmpty()) item(key = "suggestions") {
        ChipRow(state.suggestions.map { ChipOption(it, it) }, null, { onAction(SearchAction.PickSuggestion(it)) })
    }
    if (state.interpretation.isNotEmpty()) item(key = "interpretation") {
        Text(state.interpretation.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LocalDimens.current.gutter, vertical = 4.dp).testTag("interpretation"))
    }
    // The user's own titles first: they play from Telegram.
    val local = state.results
    val mine = (local as? UiState.Content)?.value?.let { listOfNotNull(it.top) + it.rest }.orEmpty()
    when (local) {
        UiState.Loading, null -> item(key = "local-loading") { SkeletonRow() }
        is UiState.Error -> item(key = "local-error") { Notice(R.string.search_failed_title, null, "search-failed") { onAction(SearchAction.Retry) } }
        else -> if (mine.isNotEmpty()) item(key = "local") {
            val tag = stringResource(R.string.home_from_telegram)
            MediaRow(stringResource(R.string.search_your_titles), mine, tileCard(), { onOpen(it.open) },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("telegram-results"), tag = tag)
        }
    }
    val addons = state.addons
    addons?.rows?.forEach { r ->
        item(key = "addon:${r.key}") {
            val title = r.addon?.let { stringResource(R.string.search_row_title, it, typeLabel(r.type)) } ?: stringResource(R.string.search_more_titles)
            MediaRow(title, r.tiles, tileCard(), { onOpen(it.open) }, modifier = Modifier.testTag("addon-results:${r.key}"))
        }
    }
    if (state.addonsLoading) item(key = "addons-loading") { SkeletonRow(modifier = Modifier.testTag("addons-loading")) }
    // Nothing anywhere (once everything answered): Nuvio's messages.
    val brainShown = state.brain?.tiles?.isNotEmpty() == true
    val localDone = local is UiState.Content || local is UiState.Empty
    if (localDone && mine.isEmpty() && !brainShown && !state.addonsLoading && addons?.rows.isNullOrEmpty()) {
        item(key = "empty") {
            when {
                addons?.failed == true -> Notice(R.string.search_failed_title, R.string.search_failed_message, "search-failed") { onAction(SearchAction.Retry) }
                addons != null && !addons.searchable -> Notice(R.string.search_no_catalogs_title, R.string.search_no_catalogs_message, "search-no-catalogs")
                else -> Notice(R.string.search_empty_title, R.string.search_empty_message, "search-empty")
            }
        }
    }
}

/** An empty or failed state as a card in the list, with Retry when [onRetry] is given. */
@Composable
private fun Notice(title: Int, message: Int?, tag: String, onRetry: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = LocalDimens.current.gutter, vertical = 12.dp).testTag(tag),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            onRetry?.let { OutlinedButton(onClick = it, modifier = Modifier.focusHighlight().testTag("$tag-retry")) { Text(stringResource(R.string.search_retry)) } }
        }
    }
}

@Composable
private fun typeLabel(t: CatalogType) = stringResource(if (t == CatalogType.SERIES) R.string.search_series else R.string.search_movies)

private const val ALL = "\u0000all"

/** The request's answer: the domain's sentence, what was understood, one-click play, the results. */
@Composable
private fun Brain(b: BrainAnswer, onOpen: (OpenTarget) -> Unit) {
    val card = tileCard()
    val d = LocalDimens.current
    Column(Modifier.padding(horizontal = d.gutter, vertical = 8.dp).testTag("brain"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.search_brain_heading), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(b.message, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag("brain-message"))
        if (b.understood.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.understood.forEach { u ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(u, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
        b.play?.let { p ->
            Button(onClick = { onOpen(p) }, modifier = Modifier.focusHighlight().testTag("brain-play")) {
                Icon(Icons.Filled.PlayArrow, null); Text(stringResource(R.string.search_brain_play), Modifier.padding(start = 6.dp))
            }
        }
        if (b.tiles.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(d.cardGap), modifier = Modifier.testTag("brain-results")) {
                items(b.tiles.size) { i ->
                    val t = b.tiles[i]
                    MediaCard(card(t), { onOpen(t.open) }, Modifier.width(d.posterWidth))
                }
            }
        }
    }
}
