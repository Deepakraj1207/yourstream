package dev.yourstream.ui.core

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems

object StateTags {
    const val LOADING = "state:loading"
    const val EMPTY = "state:empty"
    const val ERROR = "state:error"
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.ui_loading)
    Box(modifier.fillMaxSize().testTag(StateTags.LOADING).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Text and call to action for each [EmptyKind]. [onAction] null = no button. */
@Composable
fun EmptyState(kind: EmptyKind, modifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    val (title, message, action) = emptyTexts(kind)
    Message(StateTags.EMPTY, emptyIcon(kind), stringResource(title), message?.let { stringResource(it) }, action?.takeIf { onAction != null }?.let { stringResource(it) }, onAction, modifier)
}

@Composable
fun ErrorState(
    error: UiError,
    modifier: Modifier = Modifier,
    /** More ways out, shown under Retry (e.g. the player's "Play next episode instead"). */
    extraActions: (@Composable RowScope.() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    Message(
        StateTags.ERROR, if (error.kind == ErrorKind.OFFLINE) Icons.Filled.Refresh else Icons.Filled.Warning, stringResource(errorText(error.kind)), error.detail,
        if (error.retryable && onRetry != null) stringResource(R.string.ui_retry) else null, onRetry, modifier, polite = false, extraActions,
    )
}

@Composable
private fun Message(
    tag: String, icon: ImageVector, title: String, detail: String?, action: String?, onAction: (() -> Unit)?, modifier: Modifier,
    polite: Boolean = true, extraActions: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(LocalDimens.current.gutter).testTag(tag), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).semantics { liveRegion = if (polite) LiveRegionMode.Polite else LiveRegionMode.Assertive },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (action != null && onAction != null) Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp).focusHighlight(MaterialTheme.shapes.extraLarge).testTag("state-action")) { Text(action) }
            extraActions?.let { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
        }
    }
}

/**
 * Renders a [UiState]: spinner, empty message, error with retry, or [content]. The single way
 * screens show their state, so every screen behaves the same when loading or failing.
 */
@Composable
fun <T> StateContent(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onEmptyAction: (() -> Unit)? = null,
    loading: @Composable () -> Unit = { LoadingState(modifier) },
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> loading()
        is UiState.Empty -> EmptyState(state.kind, modifier, onEmptyAction)
        is UiState.Error -> ErrorState(state.error, modifier, onRetry = onRetry)
        is UiState.Content -> Box(modifier) { content(state.value) }
    }
}

/** The same for Paging lists: first-load spinner, empty, error with retry, else [content]. */
@Composable
fun <T : Any> PagedContent(
    items: LazyPagingItems<T>,
    empty: EmptyKind,
    modifier: Modifier = Modifier,
    onEmptyAction: (() -> Unit)? = null,
    errorOf: (Throwable) -> UiError = { it.toUiError() },
    loading: @Composable () -> Unit = { SkeletonGrid(modifier = modifier) },
    content: @Composable (LazyPagingItems<T>) -> Unit,
) {
    val refresh = items.loadState.refresh
    when {
        refresh is LoadState.Loading && items.itemCount == 0 -> loading()
        refresh is LoadState.Error && items.itemCount == 0 -> ErrorState(errorOf(refresh.error), modifier) { items.retry() }
        refresh is LoadState.NotLoading && items.itemCount == 0 -> EmptyState(empty, modifier, onEmptyAction)
        else -> Box(modifier) { content(items) }
    }
}

private data class EmptyTexts(@StringRes val title: Int, @StringRes val message: Int?, @StringRes val action: Int?)

private fun emptyTexts(kind: EmptyKind): EmptyTexts = when (kind) {
    EmptyKind.NOT_SIGNED_IN -> EmptyTexts(R.string.empty_not_signed_in_title, R.string.empty_not_signed_in_message, R.string.empty_not_signed_in_action)
    EmptyKind.NO_SOURCES -> EmptyTexts(R.string.empty_no_sources_title, R.string.empty_no_sources_message, R.string.empty_no_sources_action)
    EmptyKind.LIBRARY_EMPTY -> EmptyTexts(R.string.empty_library_title, R.string.empty_library_message, null)
    EmptyKind.MY_LIBRARY_EMPTY -> EmptyTexts(R.string.empty_my_library_title, R.string.empty_my_library_message, null)
    EmptyKind.NO_RESULTS -> EmptyTexts(R.string.empty_no_results_title, R.string.empty_no_results_message, null)
    EmptyKind.NOTHING_IN_PROGRESS -> EmptyTexts(R.string.empty_in_progress_title, R.string.empty_in_progress_message, null)
    EmptyKind.NO_FAVORITES -> EmptyTexts(R.string.empty_favorites_title, R.string.empty_favorites_message, null)
    EmptyKind.NO_EPISODES -> EmptyTexts(R.string.empty_episodes_title, R.string.empty_episodes_message, null)
    EmptyKind.NO_CHATS -> EmptyTexts(R.string.empty_chats_title, R.string.empty_chats_message, null)
    EmptyKind.NOTHING_TO_REVIEW -> EmptyTexts(R.string.empty_review_title, R.string.empty_review_message, null)
    EmptyKind.NO_HISTORY -> EmptyTexts(R.string.empty_history_title, R.string.empty_history_message, null)
    EmptyKind.GENERIC -> EmptyTexts(R.string.empty_generic_title, null, null)
}

@StringRes
private fun errorText(kind: ErrorKind): Int = when (kind) {
    ErrorKind.OFFLINE -> R.string.error_offline
    ErrorKind.TELEGRAM -> R.string.error_telegram
    ErrorKind.SIGNED_OUT -> R.string.error_signed_out
    ErrorKind.NOT_FOUND -> R.string.error_not_found
    ErrorKind.SERVICE -> R.string.error_service
    ErrorKind.INPUT -> R.string.error_input
    ErrorKind.UNKNOWN -> R.string.error_unknown
}

private fun emptyIcon(kind: EmptyKind): ImageVector = when (kind) {
    EmptyKind.NOT_SIGNED_IN -> Icons.Filled.AccountCircle
    EmptyKind.NO_SOURCES, EmptyKind.NO_CHATS, EmptyKind.LIBRARY_EMPTY, EmptyKind.MY_LIBRARY_EMPTY, EmptyKind.NO_EPISODES -> Icons.Filled.List
    EmptyKind.NO_RESULTS -> Icons.Filled.Search
    EmptyKind.NOTHING_IN_PROGRESS -> Icons.Filled.PlayArrow
    EmptyKind.NO_FAVORITES -> Icons.Filled.Favorite
    EmptyKind.NOTHING_TO_REVIEW -> Icons.Filled.CheckCircle
    EmptyKind.NO_HISTORY -> Icons.Filled.PlayArrow
    EmptyKind.GENERIC -> Icons.Filled.Info
}
