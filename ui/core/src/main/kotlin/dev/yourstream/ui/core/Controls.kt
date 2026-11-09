package dev.yourstream.ui.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** One option of a chip group. */
data class ChipOption<T>(val value: T, val label: String)

/** A single-choice row of filter chips (kind, sort, watch state). Scrolls when it does not fit. */
@Composable
fun <T> ChipRow(options: List<ChipOption<T>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LocalWindowLayout.current.gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            FilterChip(selected = o.value == selected, onClick = { onSelect(o.value) }, label = { Text(o.label) }, modifier = Modifier.focusHighlight())
        }
    }
}

/**
 * Screen frame: title bar (with back when [onBack] is given) and the content below. On TV the
 * title bar is just a heading (back is the remote's BACK key).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (onBack != null && !LocalWindowLayout.current.isTv) {
                    IconButton(onClick = onBack, modifier = Modifier.focusHighlight()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back))
                    }
                }
            },
            actions = { actions() },
        )
        content()
    }
}
