package dev.yourstream.ui.screens.favorites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yourstream.ui.core.MediaGrid
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.OpenTarget

@Composable
fun FavoritesRoute(onOpen: (OpenTarget) -> Unit, vm: FavoritesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    FavoritesScreen(state, vm::onAction, onOpen)
}

@Composable
fun FavoritesScreen(state: FavoritesUiState, onAction: (FavoritesAction) -> Unit, onOpen: (OpenTarget) -> Unit) {
    ScreenScaffold(stringResource(R.string.nav_favorites)) {
        StateContent(state.items, onRetry = { onAction(FavoritesAction.Retry) }) { items ->
            MediaGrid(items, { it.tile.card }, { onOpen(it.tile.open) })
        }
    }
}
