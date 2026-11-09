package dev.yourstream.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.metadata.api.MetadataRef
import dev.yourstream.metadata.core.match.ScoredCandidate
import dev.yourstream.metadata.sync.ConfirmMetadataMatch
import dev.yourstream.metadata.sync.MetadataRepository
import dev.yourstream.metadata.sync.RejectMetadataMatch
import dev.yourstream.metadata.sync.SearchMetadataCandidates
import dev.yourstream.ui.core.Artwork
import dev.yourstream.ui.core.EmptyKind
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.map as mapState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.core.toUiState
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.FixMatchRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** A possible identity for a title, from a metadata provider. */
data class MatchCandidate(
    val ref: MetadataRef,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val overview: String?,
    /** How well it fits the file's title and year, 0..100 (from the matcher's score). */
    val percent: Int?,
)

/** A library title and the candidates to choose from. */
data class MatchEntry(
    val itemId: Long,
    val title: String,
    val year: Int?,
    val candidates: List<MatchCandidate>,
    /** True when [candidates] come from the user's own search. */
    val searched: Boolean = false,
)

data class MatchUiState(
    val entries: UiState<List<MatchEntry>> = UiState.Loading,
    /** Titles with a confirm/reject/search in flight. */
    val busy: Set<Long> = emptySet(),
    val error: UiError? = null,
    /** Fix-one mode (from Details): leave the screen once a choice is made. */
    val done: Boolean = false,
)

sealed interface MatchAction {
    data class Confirm(val itemId: Long, val ref: MetadataRef) : MatchAction
    /** "None of these": keep the title with its filename details; never suggested again. */
    data class Reject(val itemId: Long) : MatchAction
    data class Search(val itemId: Long, val title: String, val year: Int?) : MatchAction
}

fun ScoredCandidate.toCandidate() = MatchCandidate(
    candidate.ref, candidate.title, candidate.year, candidate.posterUrl, candidate.overview?.take(240), (confidence * 100).roundToInt().coerceIn(0, 100),
)

/** Shared actions over the matcher's use cases; user choices are final (the matcher never overrides them). */
abstract class MatchViewModelBase(
    private val confirm: ConfirmMetadataMatch,
    private val reject: RejectMetadataMatch,
    private val search: SearchMetadataCandidates,
) : ViewModel() {
    protected val busy = MutableStateFlow<Set<Long>>(emptySet())
    protected val error = MutableStateFlow<UiError?>(null)
    protected val searched = MutableStateFlow<Map<Long, List<MatchCandidate>>>(emptyMap())
    protected val done = MutableStateFlow(false)

    abstract val state: StateFlow<MatchUiState>

    fun onAction(action: MatchAction) {
        val id = when (action) { is MatchAction.Confirm -> action.itemId; is MatchAction.Reject -> action.itemId; is MatchAction.Search -> action.itemId }
        busy.update { it + id }
        error.value = null
        viewModelScope.launch {
            try {
                when (action) {
                    is MatchAction.Confirm -> when (val r = confirm(action.itemId, action.ref)) {
                        is AppResult.Err -> error.value = r.error.toUiError()
                        is AppResult.Ok -> { searched.update { it - id }; done.value = true }
                    }
                    is MatchAction.Reject -> { reject(action.itemId); searched.update { it - id }; done.value = true }
                    is MatchAction.Search -> {
                        val outcome = search(action.itemId, action.title.trim().ifEmpty { null }, action.year)
                        outcome?.error?.let { error.value = it.toUiError() }
                        searched.update { it + (id to outcome?.candidates.orEmpty().map { c -> c.toCandidate() }) }
                    }
                }
            } catch (t: Throwable) {
                error.value = t.toUiError()
            } finally {
                busy.update { it - id }
            }
        }
    }
}

/** Every title the matcher was unsure about, with its suggestions. */
@HiltViewModel
class MatchReviewViewModel @Inject constructor(
    metadata: MetadataRepository,
    confirm: ConfirmMetadataMatch,
    reject: RejectMetadataMatch,
    search: SearchMetadataCandidates,
) : MatchViewModelBase(confirm, reject, search) {
    private val queue: Flow<UiState<List<MatchEntry>>> = metadata.reviewQueue()
        .map<_, UiState<List<MatchEntry>>> { list -> list.map { MatchEntry(it.itemId, it.title, it.year, it.candidates.map { c -> c.toCandidate() }) }.toUiState(EmptyKind.NOTHING_TO_REVIEW) }
        .catch { emit(UiState.Error(it.toUiError())) }

    override val state: StateFlow<MatchUiState> = combine(queue, searched, busy, error) { q, s, b, e ->
        MatchUiState(q.mapState { list -> list.map { entry -> s[entry.itemId]?.let { entry.copy(candidates = it, searched = true) } ?: entry } }, b, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchUiState())
}

/** "Fix match" for one title: fresh candidates for its title and year, or the user's own search. */
@HiltViewModel
class FixMatchViewModel @Inject constructor(
    saved: SavedStateHandle,
    media: MediaRepository,
    confirm: ConfirmMetadataMatch,
    reject: RejectMetadataMatch,
    private val searchCandidates: SearchMetadataCandidates,
) : MatchViewModelBase(confirm, reject, searchCandidates) {
    private val itemId = saved.toRoute<FixMatchRoute>().itemId

    private val entry: Flow<UiState<List<MatchEntry>>> = flow {
        emit(UiState.Loading)
        val item = media.getItem(itemId)?.summary
        if (item == null) { emit(UiState.Empty(EmptyKind.NO_RESULTS)); return@flow }
        val outcome = runCatching { searchCandidates(itemId) }.getOrNull()
        outcome?.error?.let { error.value = it.toUiError() }
        emit(UiState.Content(listOf(MatchEntry(itemId, item.title, item.year, outcome?.candidates.orEmpty().map { it.toCandidate() }))))
    }.catch { emit(UiState.Error(it.toUiError())) }

    override val state: StateFlow<MatchUiState> = combine(entry, searched, busy, error, done) { q, s, b, e, d ->
        MatchUiState(q.mapState { list -> list.map { en -> s[en.itemId]?.let { en.copy(candidates = it, searched = true) } ?: en } }, b, e, d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchUiState())
}

@Composable
fun MatchReviewDestination(onBack: () -> Unit, vm: MatchReviewViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    MatchScreen(stringResource(R.string.review_title), state, vm::onAction, onBack)
}

@Composable
fun FixMatchDestination(onBack: () -> Unit, vm: FixMatchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.done) { if (state.done) onBack() }
    MatchScreen(stringResource(R.string.fix_match_title), state, vm::onAction, onBack)
}

/**
 * One card per title: what the file says, then the candidates as posters (choose = confirm),
 * "None of these" and a search by title and year. Every choice is the user's; the matcher never
 * applies a suggestion below its confidence threshold on its own.
 */
@Composable
fun MatchScreen(title: String, state: MatchUiState, onAction: (MatchAction) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(title, onBack = onBack) {
        Column {
            state.error?.let { Text(it.detail ?: stringResource(R.string.review_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = LocalDimens.current.gutter).testTag("match-error")) }
            StateContent(state.entries) { entries ->
                LazyColumn(
                    Modifier.testTag("match-list"),
                    contentPadding = PaddingValues(horizontal = LocalDimens.current.gutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { Text(stringResource(R.string.review_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(entries, key = { it.itemId }) { e -> EntryCard(e, e.itemId in state.busy, onAction) }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(e: MatchEntry, busy: Boolean, onAction: (MatchAction) -> Unit) {
    var searching by rememberSaveable(e.itemId) { mutableStateOf(false) }
    var query by rememberSaveable(e.itemId) { mutableStateOf(e.title) }
    var year by rememberSaveable(e.itemId) { mutableStateOf(e.year?.toString().orEmpty()) }
    OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 1000.dp).testTag("match:${e.itemId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.review_from_file), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(e.title, e.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.titleLarge)
            if (e.candidates.isEmpty()) {
                Text(stringResource(if (e.searched) R.string.review_no_results else R.string.review_no_candidates), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.review_pick), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(e.candidates, key = { it.ref.provider.value + ":" + it.ref.id }) { c -> CandidateCard(c, enabled = !busy) { onAction(MatchAction.Confirm(e.itemId, c.ref)) } }
                }
            }
            if (searching) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.review_search_title)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onAction(MatchAction.Search(e.itemId, query, year.toIntOrNull())) }),
                        modifier = Modifier.weight(1f).testTag("search-title:${e.itemId}"))
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.review_search_year)) }, singleLine = true,
                        modifier = Modifier.width(110.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (searching) Button(onClick = { onAction(MatchAction.Search(e.itemId, query, year.toIntOrNull())) }, enabled = !busy && query.isNotBlank(),
                    modifier = Modifier.focusHighlight().testTag("search-go:${e.itemId}")) { Text(stringResource(R.string.review_search)) }
                else OutlinedButton(onClick = { searching = true }, enabled = !busy, modifier = Modifier.focusHighlight().testTag("search:${e.itemId}")) { Text(stringResource(R.string.review_search_other)) }
                TextButton(onClick = { onAction(MatchAction.Reject(e.itemId)) }, enabled = !busy, modifier = Modifier.focusHighlight().testTag("reject:${e.itemId}")) {
                    Text(stringResource(R.string.review_none))
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(c: MatchCandidate, enabled: Boolean, onClick: () -> Unit) {
    val desc = listOfNotNull(c.title, c.year?.toString(), c.percent?.let { stringResource(R.string.review_match_percent, it) }).joinToString(", ")
    Column(
        Modifier.width(LocalDimens.current.posterWidth).focusHighlight(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .testTag("candidate:${c.ref.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Artwork(c.posterUrl, c.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium))
        Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(c.year?.toString(), c.percent?.let { stringResource(R.string.review_match_percent, it) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
