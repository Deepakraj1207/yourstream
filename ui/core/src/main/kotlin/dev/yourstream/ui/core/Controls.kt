package dev.yourstream.ui.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One option of a chip group. */
data class ChipOption<T>(val value: T, val label: String)

/** A single-choice row of filter chips. Scrolls when it does not fit. */
@Composable
fun <T> ChipRow(options: List<ChipOption<T>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LocalDimens.current.gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            FilterChip(
                selected = o.value == selected, onClick = { onSelect(o.value) }, label = { Text(o.label) },
                leadingIcon = if (o.value == selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("chip:${o.label}"),
            )
        }
    }
}

/** Segmented single choice (All / Movies / Series / Videos). Stays compact; scrolls on narrow phones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<ChipOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LocalDimens.current.gutter)) {
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o.value == selected, onClick = { onSelect(o.value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag("segment:${o.label}"),
                ) { Text(o.label, maxLines = 1) }
            }
        }
    }
}

/** "Sort: Recently added ▾" — a menu, so sort options do not take a whole row on phones. */
@Composable
fun <T> SortMenu(label: String, options: List<ChipOption<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }?.label.orEmpty()
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.focusHighlight(MaterialTheme.shapes.extraLarge).testTag("sort")) { Text("$label: $current") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label) }, onClick = { open = false; onSelect(o.value) },
                    leadingIcon = if (o.value == selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                    modifier = Modifier.testTag("sort:${o.label}"),
                )
            }
        }
    }
}

/**
 * Screen frame: large title (with an optional [count] such as "1,240 titles"), back on touch
 * layouts when [onBack] is given (TV uses the remote's BACK key), actions, then the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    count: String? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val tv = LocalWindowLayout.current.isTv
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = if (tv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    count?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp).testTag("count")) }
                }
            },
            navigationIcon = {
                if (onBack != null && !tv) {
                    IconButton(onClick = onBack, modifier = Modifier.focusHighlight()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back))
                    }
                }
            },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.padding(horizontal = if (tv) LocalDimens.current.gutter - 16.dp else 0.dp),
        )
        content()
    }
}
