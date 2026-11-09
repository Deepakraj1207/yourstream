package dev.yourstream.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
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
import dev.yourstream.ui.screens.tileCard
import androidx.compose.material.icons.filled.PlayArrow
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
                StateContent(results, loading = { SkeletonGrid() }) { r -> Results(r, state.brain, onOpen) }
            }
        }
    }
}

/** Blank query: what kinds of searches work (the engine's own capabilities, not sample data). */
@Composable
private fun Idle(onAction: (SearchAction) -> Unit) {
    val examples = listOf(R.string.search_example_4, R.string.search_example_1, R.string.search_example_2, R.string.search_example_5, R.string.search_example_3).map { stringResource(it) }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.search_examples), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LocalDimens.current.gutter))
        ChipRow(examples.map { ChipOption(it, it) }, null, { onAction(SearchAction.QueryChanged(it)) })
    }
}

@Composable
private fun Results(r: SearchResults, brain: BrainAnswer?, onOpen: (OpenTarget) -> Unit) {
    val count = pluralStringResource(R.plurals.results_count, r.total, r.total)
    val card = tileCard()
    MediaGrid(
        r.rest, card, { onOpen(it.open) },
        header = {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                brain?.let { Brain(it, card, onOpen) }
                if (brain != null && r.rest.isNotEmpty()) GridHeading(stringResource(R.string.search_title_matches))
                if (brain == null || r.rest.isNotEmpty()) Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("results-count"))
                r.top?.let { top ->
                    GridHeading(stringResource(R.string.search_top_result))
                    MediaCard(top.card, { onOpen(top.open) }, Modifier.widthIn(max = LocalDimens.current.wideCardWidth * 1.4f).padding(vertical = 8.dp).testTag("top-result"), CardStyle.WIDE)
                    if (r.rest.isNotEmpty()) GridHeading(stringResource(R.string.search_more_results))
                }
            }
        },
    )
}

/** The request's answer: the domain's sentence, what was understood, one-click play, the results. */
@Composable
private fun Brain(b: BrainAnswer, card: (Tile) -> dev.yourstream.ui.core.MediaCardModel, onOpen: (OpenTarget) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp).testTag("brain"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.search_brain_heading), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(b.message, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag("brain-message"))
        if (b.understood.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.understood.forEach { u ->
                    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(u, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
        b.play?.let { p ->
            androidx.compose.material3.Button(onClick = { onOpen(p) }, modifier = Modifier.focusHighlight().testTag("brain-play")) {
                Icon(Icons.Filled.PlayArrow, null); Text(stringResource(R.string.search_brain_play), Modifier.padding(start = 6.dp))
            }
        }
        if (b.tiles.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.cardGap), modifier = Modifier.testTag("brain-results")) {
                items(b.tiles.size) { i ->
                    val t = b.tiles[i]
                    MediaCard(card(t), { onOpen(t.open) }, Modifier.width(LocalDimens.current.posterWidth))
                }
            }
        }
    }
}

/** A heading inside the grid (the grid already applies the gutter). */
@Composable
private fun GridHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
}
