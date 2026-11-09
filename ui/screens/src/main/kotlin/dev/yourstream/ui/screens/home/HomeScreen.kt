package dev.yourstream.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.EmptyState
import dev.yourstream.ui.core.ErrorState
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.MediaRow
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.OpenTarget

@Composable
fun HomeRoute(onOpen: (OpenTarget) -> Unit, onSeeAll: (HomeSection) -> Unit, onSignIn: () -> Unit, onChooseSources: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    HomeScreen(state, vm::onAction, onOpen, onSeeAll, onSignIn, onChooseSources)
}

/** Stateless: renders [HomeUiState]; every user intent goes out through the callbacks. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpen: (OpenTarget) -> Unit,
    onSeeAll: (HomeSection) -> Unit,
    onSignIn: () -> Unit,
    onChooseSources: () -> Unit,
) {
    ScreenScaffold(stringResource(R.string.nav_home)) {
        val blocking = state.blocking
        if (blocking != null) {
            EmptyState(blocking, onAction = when (blocking) {
                EmptyKind.NOT_SIGNED_IN -> onSignIn
                EmptyKind.NO_SOURCES -> onChooseSources
                else -> null
            })
            return@ScreenScaffold
        }
        LazyColumn(Modifier.fillMaxSize().testTag("home"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.banner?.let { b -> item(key = "banner") { Banner(b, onSignIn, onChooseSources) } }
            section(R.string.home_continue, state.continueWatching, HomeSection.CONTINUE_WATCHING, onOpen, onSeeAll, onAction)
            section(R.string.home_recently_added, state.recentlyAdded, HomeSection.RECENTLY_ADDED, onOpen, onSeeAll, onAction)
            section(R.string.home_favorites, state.favorites, HomeSection.FAVORITES, onOpen, onSeeAll, onAction)
        }
    }
}

/** A row only appears when it has content; a failed row shows its error in place. */
private fun LazyListScope.section(
    title: Int, state: UiState<List<Tile>>, section: HomeSection,
    onOpen: (OpenTarget) -> Unit, onSeeAll: (HomeSection) -> Unit, onAction: (HomeAction) -> Unit,
) {
    when (state) {
        is UiState.Content -> item(key = section.name) {
            val tiles = state.value
            MediaRow(stringResource(title), tiles, Tile::card, { onOpen(it.open) }, onSeeAll = { onSeeAll(section) })
        }
        is UiState.Error -> item(key = section.name) { ErrorState(state.error, Modifier.padding(16.dp)) { onAction(HomeAction.Retry) } }
        else -> Unit
    }
}

@Composable
private fun Banner(banner: HomeBanner, onSignIn: () -> Unit, onChooseSources: () -> Unit) {
    val (text, action) = when (banner) {
        HomeBanner.NOT_SIGNED_IN -> R.string.home_banner_signed_out to onSignIn
        HomeBanner.NO_SOURCES -> R.string.home_banner_no_sources to onChooseSources
        HomeBanner.OFFLINE -> R.string.home_banner_offline to null
        HomeBanner.INDEXING -> R.string.home_banner_indexing to null
    }
    val gutter = LocalWindowLayout.current.gutter
    if (action != null) {
        TextButton(onClick = action, modifier = Modifier.padding(horizontal = gutter).focusHighlight().testTag("banner")) { Text(stringResource(text)) }
    } else {
        Text(stringResource(text), modifier = Modifier.padding(horizontal = gutter).testTag("banner"))
    }
}
