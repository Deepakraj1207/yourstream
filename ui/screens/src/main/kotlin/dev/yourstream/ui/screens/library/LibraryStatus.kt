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
import dev.yourstream.media.api.model.IndexingPolicy
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
    val state: IndexState = IndexState.IDLE,
    /** Paused by the user. */
    val paused: Boolean = false,
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
    /** When background indexing may run (Settings → Content & discovery). */
    val policy: IndexingPolicy = IndexingPolicy(),
    /** Files browsing leaves out: no title found and no poster. */
    val untitled: Int = 0,
) {
    val allPaused: Boolean get() = sources.isNotEmpty() && sources.all { it.paused }
    val syncing: Boolean get() = sources.any { it.syncing }
    val lastSyncAt: Long? get() = sources.mapNotNull { it.lastSyncAt }.maxOrNull()
    /** Titles the matcher has not looked at yet. */
    val pending: Int get() = (titles - matched - review - unmatched - failed).coerceAtLeast(0)
    val matchedFraction: Float get() = if (titles == 0) 0f else matched.toFloat() / titles

    companion object {
        /** Pure mapping from the domain's raw state (tested). */
        fun of(
            chats: List<ChatIndexStatus>, matchCounts: Map<String, Int>, titles: Int, movies: Int, series: Int, providers: List<String>,
            policy: IndexingPolicy = IndexingPolicy(),
        ): LibraryStatus {
            val enabled = chats.filter { it.enabled }
            fun n(vararg s: MatchStatus) = s.sumOf { matchCounts[it.name] ?: 0 }
            return LibraryStatus(
                sources = enabled.map { c ->
                    SourceSync(
                        c.chatId, c.title,
                        syncing = !c.paused && (c.progress != null || c.state.isPending),
                        files = c.mediaCount, lastSyncAt = c.lastIndexedAt,
                        error = c.lastError?.takeIf { c.state == IndexState.FAILED || c.state == IndexState.RECOVERING },
                        found = c.progress?.let { it.mediaAdded + it.mediaUpdated },
                        state = c.state, paused = c.paused,
                    )
                },
                files = enabled.sumOf { it.mediaCount },
                titles = titles, movies = movies, series = series,
                matched = n(MatchStatus.ACCEPTED, MatchStatus.MANUAL),
                review = n(MatchStatus.NEEDS_REVIEW),
                unmatched = n(MatchStatus.NO_MATCH, MatchStatus.REJECTED),
                failed = n(MatchStatus.FAILED),
                providers = providers,
                policy = policy,
            )
        }
    }
}

/** What the user can do with a source's indexing (Library status). */
sealed interface SourceAction {
    val chatId: Long
    data class Pause(override val chatId: Long) : SourceAction
    data class Resume(override val chatId: Long) : SourceAction
    /** Stops the current run; the next sync continues from where it stopped. */
    data class Stop(override val chatId: Long) : SourceAction
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
        .combine(indexer.policy) { status, policy -> status.copy(policy = policy) }
        .combine(count(LibraryQuery(kinds = setOf(LibraryKind.VIDEO), includeUntitled = true, untitledOnly = true))) { status, n -> status.copy(untitled = n) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStatus())

    fun onSource(action: SourceAction) = viewModelScope.launch {
        runCatching {
            when (action) {
                is SourceAction.Pause -> indexer.pause(action.chatId)
                is SourceAction.Resume -> indexer.resume(action.chatId)
                is SourceAction.Stop -> indexer.cancel(action.chatId)
            }
        }
    }

    fun pauseAll(paused: Boolean) = viewModelScope.launch { runCatching { if (paused) indexer.pauseAll() else indexer.resumeAll() } }

    /** Re-reads every source for new files, then matches whatever is new. */
    fun syncNow() = viewModelScope.launch {
        runCatching { indexer.scheduleAll() }
        matcher.requestRun()
    }
}

@Composable
fun LibraryStatusDestination(onReview: () -> Unit, onSources: () -> Unit, onBack: () -> Unit, onUntitled: () -> Unit = {}, vm: LibraryStatusViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LibraryStatusScreen(state, onSyncNow = { vm.syncNow() }, onReview, onSources, onBack, now = System.currentTimeMillis(),
        onSource = { vm.onSource(it) }, onPauseAll = { vm.pauseAll(it) }, onUntitled = onUntitled)
}

@Composable
fun LibraryStatusScreen(
    state: LibraryStatus, onSyncNow: () -> Unit, onReview: () -> Unit, onSources: () -> Unit, onBack: () -> Unit, now: Long,
    onSource: (SourceAction) -> Unit = {}, onPauseAll: (Boolean) -> Unit = {}, onUntitled: () -> Unit = {},
) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onSyncNow, enabled = !state.syncing && !state.allPaused, modifier = Modifier.focusHighlight().testTag("sync-now")) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.status_sync_now))
                        }
                        if (state.sources.isNotEmpty()) androidx.compose.material3.OutlinedButton(
                            onClick = { onPauseAll(!state.allPaused) }, modifier = Modifier.focusHighlight().testTag("pause-all"),
                        ) { Text(stringResource(if (state.allPaused) R.string.status_resume_all else R.string.status_pause_all)) }
                    }
                }
            }
            // 2. Recognised titles
            item {
                Stage(
                    Icons.Filled.CheckCircle, stringResource(R.string.status_stage_titles),
                    pluralStringResource(R.plurals.titles_count, state.titles, state.titles),
                    stringResource(R.string.status_titles_split, state.movies, state.series), tag = "stage-titles",
                ) {
                    // Hidden from browsing (no title, no poster): listed here so they are never lost.
                    if (state.untitled > 0) androidx.compose.material3.OutlinedButton(onClick = onUntitled, modifier = Modifier.focusHighlight().testTag("untitled")) {
                        Text(pluralStringResource(R.plurals.status_untitled, state.untitled, state.untitled))
                    }
                }
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
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("source:${s.chatId}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                pluralStringResource(R.plurals.status_files, s.files, s.files),
                                sourceStateText(s, state.policy, now),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("source-state:${s.chatId}"),
                        )
                        s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    if (s.state.isActive && !s.paused) androidx.compose.material3.TextButton(
                        onClick = { onSource(SourceAction.Stop(s.chatId)) }, modifier = Modifier.focusHighlight().testTag("source-stop:${s.chatId}"),
                    ) { Text(stringResource(R.string.status_source_stop)) }
                    androidx.compose.material3.TextButton(
                        onClick = { onSource(if (s.paused) SourceAction.Resume(s.chatId) else SourceAction.Pause(s.chatId)) },
                        modifier = Modifier.focusHighlight().testTag("source-pause:${s.chatId}"),
                    ) { Text(stringResource(if (s.paused) R.string.status_source_resume else R.string.status_source_pause)) }
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


/** A source's indexing state in plain words. Null: nothing worth saying beyond the file count. */
@Composable
internal fun sourceStateText(s: SourceSync, policy: IndexingPolicy, now: Long): String? = when {
    s.paused -> stringResource(R.string.status_state_paused)
    s.state == IndexState.CONNECTING -> stringResource(R.string.status_state_connecting)
    s.state == IndexState.SCANNING || s.found != null -> s.found?.let { stringResource(R.string.status_source_found, it) } ?: stringResource(R.string.status_state_scanning)
    s.state == IndexState.PROCESSING -> stringResource(R.string.status_state_processing)
    s.state == IndexState.QUEUED -> stringResource(if (policy.wifiOnly) R.string.status_state_waiting_wifi else R.string.status_state_waiting)
    s.state == IndexState.RECOVERING -> stringResource(R.string.status_state_recovering)
    s.state == IndexState.CANCELLED -> stringResource(R.string.status_state_cancelled)
    s.state == IndexState.FAILED -> stringResource(R.string.status_state_failed)
    s.lastSyncAt != null -> DateUtils.getRelativeTimeSpanString(s.lastSyncAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    else -> null
}
