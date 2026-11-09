package dev.yourstream.ui.screens.episodes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.ListRow
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.PagedContent
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.OpenTarget

@Composable
fun EpisodesRoute(onOpen: (OpenTarget) -> Unit, onBack: () -> Unit, vm: EpisodesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    EpisodesScreen(state, vm.items.collectAsLazyPagingItems(), vm::onAction, onOpen, onBack)
}

@Composable
fun EpisodesScreen(state: EpisodesUiState, items: LazyPagingItems<EpisodeItem>, onAction: (EpisodesAction) -> Unit, onOpen: (OpenTarget) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.episodes_title), onBack = onBack) {
        StateContent(state.seasons) { tabs ->
            Column {
                state.nextUp?.let { (label, target) ->
                    Button(onClick = { onOpen(target) }, modifier = Modifier.padding(LocalWindowLayout.current.gutter).focusHighlight().testTag("next-up")) {
                        Text(stringResource(R.string.action_play_episode, label))
                    }
                }
                if (tabs.size > 1) ChipRow(tabs.map { ChipOption(it.seasonId, it.name) }, state.selectedSeasonId ?: tabs.first().seasonId, { onAction(EpisodesAction.SelectSeason(it)) })
                PagedContent(items, EmptyKind.NO_EPISODES) { paged ->
                    LazyColumn(Modifier.testTag("episodes")) {
                        items(paged.itemCount, key = paged.itemKey { it.episodeId }) { i ->
                            paged[i]?.let { e ->
                                ListRow(
                                    title = e.title, progress = e.progress,
                                    supporting = when { !e.available -> stringResource(R.string.episode_unavailable); e.watched -> stringResource(R.string.watch_watched); else -> null },
                                    onClick = if (e.available) ({ onOpen(e.play) }) else null,
                                    trailing = {
                                        TextButton(onClick = { onAction(EpisodesAction.SetWatched(e.episodeId, !e.watched)) }, modifier = Modifier.focusHighlight()) {
                                            Text(stringResource(if (e.watched) R.string.action_mark_unwatched else R.string.action_mark_watched))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
