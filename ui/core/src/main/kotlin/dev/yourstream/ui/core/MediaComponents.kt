package dev.yourstream.ui.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey

/**
 * What a card shows: presentation only. Screens map domain models to this; components never see
 * domain types. [key] must be stable and unique within a list.
 */
@Immutable
data class MediaCardModel(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    /** 0..1 watched; null = not started / not applicable. */
    val progress: Float? = null,
    /** Short label in the corner ("NEW", "4K", "3 new"). */
    val badge: String? = null,
    val posterUrl: String? = null,
)

/** A focusable, clickable poster card (artwork comes with visual polish; now a text placeholder). */
@Composable
fun MediaCard(model: MediaCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val progressText = model.progress?.let { stringResource(R.string.cd_progress, (it * 100).toInt()) }
    val description = listOfNotNull(model.title, model.subtitle, model.badge, progressText).joinToString(", ")
    Card(
        modifier = modifier
            .testTag("card:${model.key}")
            .focusHighlight()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column {
            Surface(Modifier.fillMaxWidth().aspectRatio(2f / 3f), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    model.badge?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
            model.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            Column(Modifier.padding(8.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                model.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * A titled horizontal row (Home sections). Generic over the screen's item type: [card] maps an
 * item to what is shown, [onClick] gets the item back. D-pad left/right moves through it.
 */
@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    card: (T) -> MediaCardModel,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    val layout = LocalWindowLayout.current
    Column(modifier.fillMaxWidth().testTag("row:$title")) {
        SectionHeader(title, onSeeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { card(it).key }) { item -> MediaCard(card(item), { onClick(item) }, Modifier.width(layout.cardMinWidth)) }
        }
    }
}

/** An adaptive grid of cards from a plain list. */
@Composable
fun <T> MediaGrid(items: List<T>, card: (T) -> MediaCardModel, onClick: (T) -> Unit, modifier: Modifier = Modifier) {
    val layout = LocalWindowLayout.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(layout.cardMinWidth), modifier = modifier.testTag("grid"),
        contentPadding = PaddingValues(layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { card(it).key }) { item -> MediaCard(card(item), { onClick(item) }) }
    }
}

/** An adaptive grid of cards from Paging (long library lists); items load as they scroll in. */
@Composable
fun <T : Any> PagedMediaGrid(items: LazyPagingItems<T>, card: (T) -> MediaCardModel, onClick: (T) -> Unit, modifier: Modifier = Modifier) {
    val layout = LocalWindowLayout.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(layout.cardMinWidth), modifier = modifier.testTag("grid"),
        contentPadding = PaddingValues(layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.itemCount, key = items.itemKey { card(it).key }) { i ->
            items[i]?.let { item -> MediaCard(card(item), { onClick(item) }) }
        }
    }
}

/** A focusable list row (episodes, chats, settings). */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    overline: String? = null,
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        overlineContent = overline?.let { { Text(it) } },
        supportingContent = if (supporting != null || progress != null) {
            {
                Column {
                    supporting?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                }
            }
        } else null,
        trailingContent = trailing,
        modifier = modifier
            .then(if (onClick != null) Modifier.focusHighlight().clickable(role = Role.Button, onClick = onClick) else Modifier),
    )
}

@Composable
fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LocalWindowLayout.current.gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        onSeeAll?.let { TextButton(onClick = it, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.ui_see_all)) } }
    }
}
