package dev.yourstream.ui.screens.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.MediaGrid
import dev.yourstream.ui.core.ScreenScaffold
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
    ScreenScaffold(stringResource(R.string.nav_search)) {
        Column {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(SearchAction.QueryChanged(it)) },
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { onAction(SearchAction.Clear) }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(LocalWindowLayout.current.gutter).focusHighlight().testTag("search-field"),
            )
            if (state.suggestions.isNotEmpty()) {
                ChipRow(state.suggestions.map { ChipOption(it, it) }, null, { onAction(SearchAction.PickSuggestion(it)) })
            }
            if (state.interpretation.isNotEmpty()) {
                Text(state.interpretation.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = LocalWindowLayout.current.gutter).testTag("interpretation"))
            }
            val results = state.results
            if (results == null) {
                Text(stringResource(R.string.search_examples), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(LocalWindowLayout.current.gutter))
            } else {
                StateContent(results) { tiles -> MediaGrid(tiles, Tile::card, { onOpen(it.open) }) }
            }
        }
    }
}
