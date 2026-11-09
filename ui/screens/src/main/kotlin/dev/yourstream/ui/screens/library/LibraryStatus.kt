package dev.yourstream.ui.screens.library

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.LibraryQuery
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.MediaIndexer
import dev.yourstream.media.api.model.ChatIndexStatus
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.metadata.sync.MatchStatus
import dev.yourstream.metadata.sync.MetadataRepository
import dev.yourstream.metadata.sync.MetadataSyncTrigger
import dev.yourstream.ui.core.AppIcons
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ProgressLine
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One Telegram source and how far its sync is. */
data class SourceSync(
    val chatId: Long,
    val title: String,
    val syncing: Boolean,
    val files: Int,
    val lastSyncAt: Long?,
    val error: String?,
    /** Files found so far in the running sync. */
    val found: Int?,
)

/**
 * The automatic library pipeline, stage by stage: Telegram files → recognised titles → matched with
 * posters and details → waiting for the user's check. Counts come straight from the index, the
 * library and the matcher.
 */
data class LibraryStatus(
    val sources: List<SourceSync> = emptyList(),
    val files: Int = 0,
    val titles: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    /** Accepted automatically or chosen by the user. */
    val matched: Int = 0,
    /** Suggestions below the auto-accept threshold, kept for the user. */
    val review: Int = 0,
    /** No good candidate, or the user said "none of these": still playable, with filename details. */
    val unmatched: Int = 0,
    /** Providers unreachable for now; retried automatically. */
    val failed: Int = 0,
    /** Providers matching works with ("TMDB", "Cinemeta"); empty = matching is off. */
    val providers: List<String> = emptyList(),
) {
    val syncing: Boolean get() = sources.any { it.syncing }
    val lastSyncAt: Long? get() = sources.mapNotNull { it.lastSyncAt }.maxOrNull()
    /** Titles the matcher has not looked at yet. */
    val pending: Int get() = (titles - matched - review - unmatched - failed).coerceAtLeast(0)
    val matchedFraction: Float get() = if (titles == 0) 0f else matched.toFloat() / titles

    companion object {
        /** Pure mapping from the domain's raw state (tested). */
        fun of(chats: List<ChatIndexStatus>, matchCounts: Map<String, Int>, titles: Int, movies: Int, series: Int, providers: List<String>): LibraryStatus {
            val enabled = chats.filter { it.enabled }
            fun n(vararg s: MatchStatus) = s.sumOf { matchCounts[it.name] ?: 0 }
            return LibraryStatus(
                sources = enabled.map { c ->
                    SourceSync(
                        c.chatId, c.title,
                        syncing = c.progress != null || c.state == IndexState.RUNNING || c.state == IndexState.QUEUED || c.state == IndexState.PAUSED,
                        files = c.mediaCount, lastSyncAt = c.lastIndexedAt,
                        error = c.lastError?.takeIf { c.state == IndexState.FAILED },
                        found = c.progress?.let { it.mediaAdded + it.mediaUpdated },
                    )
                },
                files = enabled.sumOf { it.mediaCount },
                titles = titles, movies = movies, series = series,
                matched = n(MatchStatus.ACCEPTED, MatchStatus.MANUAL),
                review = n(MatchStatus.NEEDS_REVIEW),
                unmatched = n(MatchStatus.NO_MATCH, MatchStatus.REJECTED),
                failed = n(MatchStatus.FAILED),
                providers = providers,
            )
        }
    }
}

@HiltViewModel
class LibraryStatusViewModel @Inject constructor(
    index: MediaIndexRepository,
    private val media: MediaRepository,
    private val metadata: MetadataRepository,
    private val indexer: MediaIndexer,
    private val matcher: MetadataSyncTrigger,
) : ViewModel() {
    private fun count(q: LibraryQuery): Flow<Int> = media.count(q).catch { emit(0) }

    private val counts = combine(count(LibraryQuery()), count(LibraryQuery(kinds = setOf(LibraryKind.MOVIE))), count(LibraryQuery(kinds = setOf(LibraryKind.SERIES)))) { a, m, s -> Triple(a, m, s) }

    val state: StateFlow<LibraryStatus> = combine(
        index.observeChats().catch { emit(emptyList()) },
        metadata.statusCounts().catch { emit(emptyMap()) },
        counts,
        flowOf(Unit).map { runCatching { metadata.configuredProviders() }.getOrDefault(emptyList()) },
    ) { chats, matches, (titles, movies, series), providers -> LibraryStatus.of(chats, matches, titles, movies, series, providers) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStatus())

    /** Re-reads every source for new files, then matches whatever is new. */
    fun syncNow() = viewModelScope.launch {
        runCatching { indexer.scheduleAll() }
        matcher.requestRun()
    }
}

@Composable
fun LibraryStatusDestination(onReview: () -> Unit, onSources: () -> Unit, onBack: () -> Unit, vm: LibraryStatusViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LibraryStatusScreen(state, onSyncNow = { vm.syncNow() }, onReview, onSources, onBack, now = System.currentTimeMillis())
}

@Composable
fun LibraryStatusScreen(state: LibraryStatus, onSyncNow: () -> Unit, onReview: () -> Unit, onSources: () -> Unit, onBack: () -> Unit, now: Long) {
    ScreenScaffold(stringResource(R.string.status_title), onBack = onBack) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).testTag("library-status"),
            contentPadding = PaddingValues(horizontal = LocalDimens.current.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    pluralStringResource(R.plurals.status_summary, state.titles, state.titles,
                        pluralStringResource(R.plurals.status_files, state.files, state.files),
                        pluralStringResource(R.plurals.status_chats, state.sources.size, state.sources.size)),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("status-summary"),
                )
            }
            // 1. Telegram files
            item {
                Stage(
                    AppIcons.Episodes, stringResource(R.string.status_stage_files),
                    pluralStringResource(R.plurals.status_files, state.files, state.files),
                    when {
                        state.syncing -> stringResource(R.string.status_syncing)
                        state.lastSyncAt != null -> stringResource(R.string.status_synced_at, DateUtils.getRelativeTimeSpanString(state.lastSyncAt!!, now, DateUtils.MINUTE_IN_MILLIS).toString())
                        else -> stringResource(R.string.status_not_synced)
                    },
                    busy = state.syncing, tag = "stage-files",
                ) {
                    FilledTonalButton(onClick = onSyncNow, enabled = !state.syncing, modifier = Modifier.focusHighlight().testTag("sync-now")) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.status_sync_now))
                    }
                }
            }
            // 2. Recognised titles
            item {
                Stage(
                    Icons.Filled.CheckCircle, stringResource(R.string.status_stage_titles),
                    pluralStringResource(R.plurals.titles_count, state.titles, state.titles),
                    stringResource(R.string.status_titles_split, state.movies, state.series), tag = "stage-titles",
                )
            }
            // 3. Posters and details
            item {
                Stage(
                    AppIcons.Movie, stringResource(R.string.status_stage_matched),
                    stringResource(R.string.status_matched_of, state.matched, state.titles),
                    when {
                        state.providers.isEmpty() -> stringResource(R.string.status_matching_off)
                        state.pending > 0 -> pluralStringResource(R.plurals.status_matching_pending, state.pending, state.pending)
                        state.failed > 0 -> pluralStringResource(R.plurals.status_matching_retry, state.failed, state.failed)
                        else -> stringResource(R.string.status_matching_done)
                    },
                    progress = state.matchedFraction, busy = state.pending > 0 && state.providers.isNotEmpty(), tag = "stage-matched",
                )
            }
            // 4. The user's check
            item {
                Stage(
                    Icons.Filled.Warning, stringResource(R.string.status_stage_review),
                    if (state.review == 0) stringResource(R.string.status_review_none) else pluralStringResource(R.plurals.status_review_count, state.review, state.review),
                    if (state.unmatched > 0) pluralStringResource(R.plurals.status_unmatched, state.unmatched, state.unmatched) else null,
                    tag = "stage-review",
                ) {
                    if (state.review > 0) Button(onClick = onReview, modifier = Modifier.focusHighlight().testTag("review")) { Text(stringResource(R.string.status_review_action)) }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.status_sources), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                    androidx.compose.material3.TextButton(onClick = onSources, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.status_manage_sources)) }
                }
            }
            items(state.sources, key = { it.chatId }) { s ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}.testTag("source:${s.chatId}")) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            pluralStringResource(R.plurals.status_files, s.files, s.files),
                            when {
                                s.syncing -> s.found?.let { stringResource(R.string.status_source_found, it) } ?: stringResource(R.string.status_syncing)
                                s.lastSyncAt != null -> DateUtils.getRelativeTimeSpanString(s.lastSyncAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
                                else -> null
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                // What leaves the device, stated plainly.
                Text(
                    if (state.providers.isEmpty()) stringResource(R.string.status_privacy_no_providers)
                    else stringResource(R.string.status_privacy, state.providers.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp).testTag("privacy-note"),
                )
            }
        }
    }
}

@Composable
private fun Stage(
    icon: ImageVector,
    title: String,
    value: String,
    detail: String?,
    progress: Float? = null,
    busy: Boolean = false,
    tag: String,
    action: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                progress?.let { ProgressLine(it, Modifier.fillMaxWidth().padding(top = 6.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant) }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            action?.invoke()
        }
    }
}
