package dev.yourstream.ui.core

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
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
    /** Short label in the corner ("4K", "+3"). */
    val badge: String? = null,
    val posterUrl: String? = null,
    /** One line under the subtitle on wide cards ("12 min left", "Next episode"). */
    val caption: String? = null,
    val watched: Boolean = false,
)

enum class CardStyle { POSTER, WIDE }

/**
 * The shared frame of every card: focus lift + border, click, long press / Menu key / "more"
 * button → [actions] menu, one merged accessibility node with the actions as custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardFrame(
    model: MediaCardModel,
    onClick: () -> Unit,
    actions: List<CardAction>,
    modifier: Modifier,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val progressText = model.progress?.let { stringResource(R.string.cd_progress, (it * 100).toInt()) }
    val watchedText = if (model.watched) stringResource(R.string.cd_watched) else null
    val description = listOfNotNull(model.title, model.subtitle, model.caption, model.badge, progressText, watchedText).joinToString(", ")
    Box(
        modifier
            .testTag("card:${model.key}")
            .focusHighlight()
            .onPreviewKeyEvent { e ->
                if (actions.isNotEmpty() && e.type == KeyEventType.KeyDown && e.key == Key.Menu) { menu = true; true } else false
            }
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = if (actions.isEmpty()) null else ({ menu = true }))
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (actions.isNotEmpty()) customActions = actions.map { a -> CustomAccessibilityAction(a.label) { a.onClick(); true } }
            },
    ) { content { menu = true } }
    if (menu) CardActionsMenu(model.title, actions) { menu = false }
}

/** A 2:3 poster card: artwork, badges, progress, title and subtitle below. */
@Composable
fun PosterCard(model: MediaCardModel, onClick: () -> Unit, modifier: Modifier = Modifier, actions: List<CardAction> = emptyList()) {
    CardFrame(model, onClick, actions, modifier) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).inkPanel(MaterialTheme.shapes.medium).clip(MaterialTheme.shapes.medium)) {
                Artwork(model.posterUrl, model.title, Modifier.fillMaxSize())
                Overlays(model)
            }
            Column(Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                model.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                // Why it is here ("Tamil · action · ★ 7.6", "Next episode"), when the screen gives a reason.
                model.caption?.takeIf { it != model.badge }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** A 16:9 card for Continue Watching: title over the artwork, progress along the bottom. */
@Composable
fun WideCard(model: MediaCardModel, onClick: () -> Unit, modifier: Modifier = Modifier, actions: List<CardAction> = emptyList()) {
    CardFrame(model, onClick, actions, modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).inkPanel(MaterialTheme.shapes.medium).clip(MaterialTheme.shapes.medium)) {
            Artwork(model.posterUrl, model.title, Modifier.fillMaxSize(), showInitials = false)
            Box(Modifier.fillMaxSize().background(BottomScrim))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(model.subtitle, model.caption).joinToString(" · ").ifEmpty { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            model.badge?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
            model.progress?.let { p ->
                ProgressLine(p, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BoxScope.Overlays(model: MediaCardModel) {
    model.badge?.let { Badge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
    if (model.watched) {
        Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(2.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
    model.progress?.let { p ->
        ProgressLine(p, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), trackColor = Color.Black.copy(alpha = 0.45f))
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = Color.Black.copy(alpha = 0.7f), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Either card style, sized for a row. */
@Composable
fun MediaCard(model: MediaCardModel, onClick: () -> Unit, modifier: Modifier = Modifier, style: CardStyle = CardStyle.POSTER, actions: List<CardAction> = emptyList()) =
    if (style == CardStyle.WIDE) WideCard(model, onClick, modifier, actions) else PosterCard(model, onClick, modifier, actions)

@Composable
private fun cardWidth(style: CardStyle) = if (style == CardStyle.WIDE) LocalDimens.current.wideCardWidth else LocalDimens.current.posterWidth

/**
 * A titled horizontal row (Home sections). Generic over the screen's item type: [card] maps an
 * item to what is shown, [onClick] gets the item back. D-pad left/right moves through it; focus
 * returns to the last focused card when the row is re-entered.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T> MediaRow(
    title: String,
    items: List<T>,
    card: (T) -> MediaCardModel,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.POSTER,
    actions: (T) -> List<CardAction> = { emptyList() },
    onSeeAll: (() -> Unit)? = null,
) {
    val d = LocalDimens.current
    Column(modifier.fillMaxWidth().testTag("row:$title")) {
        SectionHeader(title, onSeeAll)
        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = d.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(d.cardGap)) {
            items(items, key = { card(it).key }) { item -> MediaCard(card(item), { onClick(item) }, Modifier.width(cardWidth(style)), style, actions(item)) }
        }
    }
}

/** A row over Paging: skeleton while the first page loads, hidden when it turns out empty. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> PagedMediaRow(
    title: String,
    items: LazyPagingItems<T>,
    card: (T) -> MediaCardModel,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.POSTER,
    actions: (T) -> List<CardAction> = { emptyList() },
    onSeeAll: (() -> Unit)? = null,
) {
    val refresh = items.loadState.refresh
    if (items.itemCount == 0) {
        if (refresh is LoadState.Loading) SkeletonRow(title, style, modifier)
        return // empty or failed: the row is simply absent (the screen shows its own state)
    }
    val d = LocalDimens.current
    Column(modifier.fillMaxWidth().testTag("row:$title")) {
        SectionHeader(title, onSeeAll)
        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = d.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(d.cardGap)) {
            items(items.itemCount, key = items.itemKey { card(it).key }) { i ->
                items[i]?.let { item -> MediaCard(card(item), { onClick(item) }, Modifier.width(cardWidth(style)), style, actions(item)) }
            }
        }
    }
}

/** An adaptive grid from a plain list. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T> MediaGrid(
    items: List<T>,
    card: (T) -> MediaCardModel,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.POSTER,
    actions: (T) -> List<CardAction> = { emptyList() },
    header: (@Composable () -> Unit)? = null,
) {
    val d = LocalDimens.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cardWidth(style)), modifier = modifier.testTag("grid").focusRestorer(),
        contentPadding = PaddingValues(d.gutter), horizontalArrangement = Arrangement.spacedBy(d.cardGap), verticalArrangement = Arrangement.spacedBy(d.rowGap),
    ) {
        header?.let { h -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { h() } }
        items(items, key = { card(it).key }) { item -> MediaCard(card(item), { onClick(item) }, style = style, actions = actions(item)) }
    }
}

/** An adaptive grid over Paging (long library lists); items load as they scroll in. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> PagedMediaGrid(
    items: LazyPagingItems<T>,
    card: (T) -> MediaCardModel,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.POSTER,
    actions: (T) -> List<CardAction> = { emptyList() },
    header: (@Composable () -> Unit)? = null,
) {
    val d = LocalDimens.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cardWidth(style)), modifier = modifier.testTag("grid").focusRestorer(),
        contentPadding = PaddingValues(d.gutter), horizontalArrangement = Arrangement.spacedBy(d.cardGap), verticalArrangement = Arrangement.spacedBy(d.rowGap),
    ) {
        header?.let { h -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { h() } }
        items(items.itemCount, key = items.itemKey { card(it).key }) { i ->
            items[i]?.let { item -> MediaCard(card(item), { onClick(item) }, style = style, actions = actions(item)) } ?: SkeletonCard(style)
        }
        if (items.loadState.append is LoadState.Loading) items(4) { SkeletonCard(style) }
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
    leading: (@Composable () -> Unit)? = null,
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
                    progress?.let { ProgressLine(it, Modifier.fillMaxWidth().padding(top = 6.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant) }
                }
            }
        } else null,
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.then(if (onClick != null) Modifier.focusHighlight().combinedClickableCompat(onClick) else Modifier),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(role = Role.Button, onClick = onClick)

/** Section title with an optional count and "See all". */
@Composable
fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = null, count: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = LocalDimens.current.gutter, end = LocalDimens.current.gutter - 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }
                .then(if (LocalMangaStyle.current) Modifier.padding(bottom = 4.dp).inkUnderline(MaterialTheme.colorScheme.onSurface) else Modifier))
        }
        count?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }
        onSeeAll?.let { TextButton(onClick = it, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.ui_see_all)) } }
    }
}

/** A small overflow button that opens a card's menu (keyboard/touch alternative to long press). */
@Composable
fun MoreButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.focusHighlight()) { Icon(Icons.Filled.MoreVert, contentDescription = label) }
}
