package dev.yourstream.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yourstream.ui.core.CardStyle
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.MediaCard
import dev.yourstream.ui.core.MediaGrid
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.SkeletonGrid
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget

@Composable
fun SearchRoute(onOpen: (OpenTarget) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SearchScreen(state, vm::onAction, onOpen)
}

@Composable
fun SearchScreen(state: SearchUiState, onAction: (SearchAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val d = LocalDimens.current
    ScreenScaffold(stringResource(R.string.nav_search)) {
        Column {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(SearchAction.QueryChanged(it)) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { onAction(SearchAction.Clear) }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.padding(horizontal = d.gutter, vertical = 8.dp).widthIn(max = 720.dp).fillMaxWidth()
                    .focusHighlight(MaterialTheme.shapes.extraLarge).testTag("search-field"),
            )
            if (state.suggestions.isNotEmpty()) ChipRow(state.suggestions.map { ChipOption(it, it) }, null, { onAction(SearchAction.PickSuggestion(it)) })
            if (state.interpretation.isNotEmpty()) {
                Text(state.interpretation.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = d.gutter, vertical = 4.dp).testTag("interpretation"))
            }
            val results = state.results
            if (results == null) {
                Idle(onAction)
            } else {
                StateContent(results, loading = { SkeletonGrid() }) { r -> Results(r, onOpen) }
            }
        }
    }
}

/** Blank query: what kinds of searches work (the engine's own capabilities, not sample data). */
@Composable
private fun Idle(onAction: (SearchAction) -> Unit) {
    val examples = listOf(stringResource(R.string.search_example_1), stringResource(R.string.search_example_2), stringResource(R.string.search_example_3))
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.search_examples), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LocalDimens.current.gutter))
        ChipRow(examples.map { ChipOption(it, it) }, null, { onAction(SearchAction.QueryChanged(it)) })
    }
}

@Composable
private fun Results(r: SearchResults, onOpen: (OpenTarget) -> Unit) {
    val count = pluralStringResource(R.plurals.results_count, r.total, r.total)
    MediaGrid(
        r.rest, Tile::card, { onOpen(it.open) },
        header = {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("results-count"))
                r.top?.let { top ->
                    GridHeading(stringResource(R.string.search_top_result))
                    MediaCard(top.card, { onOpen(top.open) }, Modifier.widthIn(max = LocalDimens.current.wideCardWidth * 1.4f).padding(vertical = 8.dp).testTag("top-result"), CardStyle.WIDE)
                    if (r.rest.isNotEmpty()) GridHeading(stringResource(R.string.search_more_results))
                }
            }
        },
    )
}

/** A heading inside the grid (the grid already applies the gutter). */
@Composable
private fun GridHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
}
