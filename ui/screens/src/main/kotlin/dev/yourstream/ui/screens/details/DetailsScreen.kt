@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.yourstream.ui.screens.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.yourstream.ui.core.Artwork
import dev.yourstream.ui.core.ProgressLine
import dev.yourstream.ui.core.LocalDimens
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yourstream.ui.core.ListRow
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.SectionHeader
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.timeLabel

@Composable
fun DetailsRoute(onOpen: (OpenTarget) -> Unit, onBack: () -> Unit, onFixMatch: ((Long) -> Unit)? = null, vm: DetailsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    DetailsScreen(state, vm::onAction, onOpen, onBack, onFixMatch)
}

@Composable
fun DetailsScreen(state: DetailsUiState, onAction: (DetailsAction) -> Unit, onOpen: (OpenTarget) -> Unit, onBack: () -> Unit, onFixMatch: ((Long) -> Unit)? = null) {
    ScreenScaffold("", onBack = onBack) {
        StateContent(state.content, Modifier.fillMaxSize(), onRetry = { onAction(DetailsAction.Retry) }) { c ->
            val gutter = LocalWindowLayout.current.gutter
            val primaryFocus = remember { FocusRequester() }
            LazyColumn(Modifier.fillMaxSize().testTag("details")) {
                item {
                    Row(Modifier.padding(horizontal = gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Artwork(c.posterUrl, c.title, Modifier.width(LocalDimens.current.posterWidth).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                            Text(listOfNotNull(c.year?.toString(), c.languages.joinToString(", ").ifEmpty { null }, if (c.percent > 0) "${c.percent}%" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (c.percent in 1..99) ProgressLine(c.percent / 100f, Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                            // Why there is no Play button, and where the details come from.
                            if (!c.available) {
                                Text(stringResource(R.string.details_removed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("details-removed"))
                            }
                            when (c.matched) {
                                false -> Text(stringResource(R.string.details_not_matched), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("details-not-matched"))
                                else -> Unit
                            }
                            // Wraps on narrow phones instead of pushing buttons off-screen.
                            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                c.primary?.let { p ->
                                    Button(onClick = { onOpen(p.target) }, modifier = Modifier.focusRequester(primaryFocus).focusHighlight(MaterialTheme.shapes.extraLarge).testTag("primary")) {
                                        Text(when (p) {
                                            is PrimaryAction.Play -> stringResource(R.string.action_play)
                                            is PrimaryAction.Resume -> stringResource(R.string.action_resume_at, timeLabel(p.positionMs))
                                            is PrimaryAction.Episode -> stringResource(if (p.resume) R.string.action_resume_episode else R.string.action_play_episode, p.label)
                                        })
                                    }
                                }
                                FilledTonalButton(onClick = { onAction(DetailsAction.ToggleLibrary) }, modifier = Modifier.focusHighlight(MaterialTheme.shapes.extraLarge).testTag("my-library")) {
                                    Text(stringResource(if (c.inLibrary) R.string.action_remove_from_library else R.string.action_add_to_library))
                                }
                                // Wrong poster or details? The user decides the match.
                                onFixMatch?.let { fix ->
                                    androidx.compose.material3.TextButton(onClick = { fix(c.itemId) }, modifier = Modifier.focusHighlight(MaterialTheme.shapes.extraLarge).testTag("fix-match")) {
                                        Text(stringResource(R.string.action_fix_match))
                                    }
                                }
                            }
                        }
                    }
                }
                if (c.seasons.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.details_seasons)) }
                    items(c.seasons, key = { "s${it.seasonId}" }) { s ->
                        ListRow(s.name, supporting = stringResource(R.string.details_episode_count, s.episodes), onClick = { onOpen(OpenTarget.Episodes(c.itemId, s.seasonId)) })
                    }
                }
                if (c.versions.size > 1 || (c.versions.isNotEmpty() && c.seasons.isEmpty())) {
                    item { SectionHeader(stringResource(R.string.details_versions)) }
                    items(c.versions, key = { "v${it.mediaId}" }) { v ->
                        val onDevice = stringResource(R.string.details_on_device)
                        val progress = state.downloading[v.mediaId]
                        val supporting = when {
                            progress != null -> "${v.detail} · " + stringResource(R.string.details_downloading, (progress * 100).toInt())
                            v.onDevice && !v.isLocal -> "${v.detail} · $onDevice"
                            else -> v.detail
                        }
                        ListRow(v.name, supporting = supporting, onClick = { onOpen(v.target) },
                            trailing = if (v.isLocal) null else { { DownloadButton(v, progress, onAction) } },
                            modifier = Modifier.testTag("version:${v.mediaId}"))
                    }
                }
            }
            // Remote users land on the main action.
            LaunchedEffect(c.itemId) { if (c.primary != null) runCatching { primaryFocus.requestFocus() } }
        }
    }
}

/** Download a Telegram version for offline viewing, cancel it while it runs, or free its space. */
@Composable
private fun DownloadButton(v: VersionRow, progress: Float?, onAction: (DetailsAction) -> Unit) {
    val (label, action, tag) = when {
        progress != null -> Triple(R.string.details_download_cancel, DetailsAction.CancelDownload(v.mediaId), "download-cancel:${v.mediaId}")
        v.onDevice -> Triple(R.string.details_download_remove, DetailsAction.RemoveDownload(v.mediaId), "download-remove:${v.mediaId}")
        else -> Triple(R.string.details_download, DetailsAction.Download(v.mediaId), "download:${v.mediaId}")
    }
    androidx.compose.material3.TextButton(onClick = { onAction(action) }, modifier = Modifier.focusHighlight().testTag(tag)) {
        androidx.compose.material3.Text(stringResource(label))
    }
}
