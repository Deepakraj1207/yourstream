package dev.yourstream.ui.screens.addon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R

/** "Sort & filter", or "Filters · 2" when limits are set; opens [StreamFilterSheet]. */
@Composable
internal fun SortFilterButton(filter: StreamFilter, onClick: () -> Unit) {
    val active = filter.active
    FilterChip(
        selected = active > 0, onClick = onClick,
        label = { Text(if (active > 0) stringResource(R.string.streams_filters_active, active) else stringResource(R.string.streams_sort_filter), maxLines = 1) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
        modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("streams-sort-filter"),
    )
}

/** The bottom sheet: edits a copy, and [onApply] keeps it ("Show 12 streams"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamFilterSheet(filter: StreamFilter, groups: List<StreamGroup>, onApply: (StreamFilter) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        StreamFilterContent(filter, groups, onApply)
    }
}

/** The sheet's content, apart so tests can show it without a window of its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreamFilterContent(filter: StreamFilter, groups: List<StreamGroup>, onApply: (StreamFilter) -> Unit) {
    var draft by remember(filter) { mutableStateOf(filter) }
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    val names: (String) -> String = { LanguageCatalog.displayName(it) }
    val shown = draft.apply(groups, names).sumOf { it.streams.size }
    val languages = remember(groups, filter) { (languagesIn(groups) + filter.languages).distinct() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().testTag("stream-filter-sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.streams_sort_filter), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { draft = StreamFilter() }, enabled = !draft.isDefault, modifier = Modifier.testTag("stream-filter-reset")) {
                Text(stringResource(R.string.streams_filter_reset))
            }
        }
        Section(stringResource(R.string.streams_sort)) {
            listOf(
                StreamSort.BEST to R.string.streams_sort_best, StreamSort.LARGEST to R.string.streams_sort_largest,
                StreamSort.SMALLEST to R.string.streams_sort_smallest, StreamSort.SOURCE to R.string.streams_sort_source,
            ).forEach { (sort, label) -> Option(stringResource(label), draft.sort == sort, "sort:${sort.name}") { draft = draft.copy(sort = sort) } }
        }
        Section(stringResource(R.string.streams_quality)) {
            listOf(StreamQuality.UHD to "4K", StreamQuality.FHD to "1080p", StreamQuality.HD to "720p", StreamQuality.SD to "SD").forEach { (q, label) ->
                Option(label, q in draft.qualities, "quality:${q.name}") { draft = draft.copy(qualities = draft.qualities.toggle(q)) }
            }
        }
        Section(stringResource(R.string.streams_source)) {
            Option(stringResource(R.string.streams_source_telegram), draft.telegram, "source:telegram") { draft = draft.copy(telegram = !draft.telegram) }
            Option(stringResource(R.string.streams_source_addons), draft.addons, "source:addons") { draft = draft.copy(addons = !draft.addons) }
        }
        Section(stringResource(R.string.streams_type)) {
            Option(stringResource(R.string.streams_type_direct), draft.direct, "type:direct") { draft = draft.copy(direct = !draft.direct) }
            Option(stringResource(R.string.streams_type_torrent), draft.torrents, "type:torrent") { draft = draft.copy(torrents = !draft.torrents) }
        }
        if (languages.isNotEmpty()) {
            Section(stringResource(R.string.streams_audio)) {
                languages.forEach { code ->
                    Option(names(code), code in draft.languages, "language:$code") { draft = draft.copy(languages = draft.languages.toggle(code)) }
                }
            }
        }
        Button(
            onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.CONFIRM); onApply(draft) }, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(bottom = 12.dp).focusHighlight(CircleShape).testTag("stream-filter-apply"),
        ) { Text(pluralStringResource(R.plurals.streams_show, shown, shown), fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Option(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    FilterChip(
        selected = selected, onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.SELECT); onClick() }, label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
        modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("stream-filter:$tag"),
    )
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

/** Audio languages the streams list or name ("Tamil", "ITA"), most common first. */
internal fun languagesIn(groups: List<StreamGroup>): List<String> = groups.flatMap { it.streams }
    .flatMap { s -> (s.languages + LanguageCatalog.findInText(listOfNotNull(s.name, s.description, s.fileName).joinToString(" "))).distinct() }
    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
