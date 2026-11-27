package dev.yourstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.desktop.addons.AddonService
import dev.yourstream.desktop.data.LibraryBuilder
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.library.PosterService
import kotlinx.coroutines.delay

/**
 * Search, like the Android app's (and Nuvio's): one big field; your Telegram titles first, then
 * the add-ons' movies and series, each as a row.
 */
@Composable
fun SearchScreen(titles: List<LibraryTitle>, service: AddonService, posters: PosterService, onOpenTitle: (LibraryTitle) -> Unit, onOpenItem: (CatalogItem) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<dev.yourstream.catalog.core.SearchSection>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val q = query.trim()
    LaunchedEffect(q) {
        found = null
        if (q.length < 2) return@LaunchedEffect
        delay(400) // after typing stops
        searching = true
        found = runCatching { service.searchSections(q) }.getOrDefault(emptyList())
        searching = false
    }
    val telegram = remember(titles, q) { if (q.length < 2) emptyList() else LibraryBuilder.search(titles, q).take(40) }
    val sections = found.orEmpty().filter { it.items.isNotEmpty() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize().testTag("search"), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item(key = "field") {
            Column(Modifier.fillMaxWidth().padding(start = LocalNavInset.current + 16.dp, end = 40.dp, top = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                PageTitle("Search")
                SearchField(query, { query = it }, "Search movies, series and your Telegram library", Modifier.widthIn(max = 760.dp).focusRequester(focus))
            }
        }
        when {
            q.length < 2 -> item(key = "hint") {
                Text(
                    "Type a title. Your Telegram library is searched as you type; add-ons are asked when you stop.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = LocalNavInset.current + 16.dp),
                )
            }
            else -> {
                if (telegram.isNotEmpty()) item(key = "telegram") {
                    CardRow("From Telegram", telegram.size, onSeeAll = null) {
                        items(telegram, key = { it.key }) { t -> TelegramCard(t, posters, Modifier.width(168.dp)) { onOpenTitle(t) } }
                    }
                }
                sections.forEach { sec ->
                    val kind = if (sec.type == CatalogType.SERIES) "Series" else "Movies"
                    val title = sec.providerName?.let { "$it · $kind" } ?: kind
                    item(key = "s:$title") {
                        CardRow(title, sec.items.size, onSeeAll = null) {
                            items(sec.items, key = { it.ref.provider.value + it.ref.id }) { i ->
                                CatalogItemCard(i, posters, libraryMatches(titles, i.title, i.year, i.ref.type == CatalogType.SERIES).isNotEmpty(), Modifier.width(168.dp)) { onOpenItem(i) }
                            }
                        }
                    }
                }
                item(key = "status") {
                    Row(Modifier.padding(start = LocalNavInset.current + 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (searching || (found == null)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Asking the add-ons…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (telegram.isEmpty() && sections.isEmpty()) {
                            Text("Nothing found for \"$q\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(40.dp)) }
    }
}

/** A large rounded search field with a clear button. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 58.dp) {
    val manga = LocalMangaStyle.current
    val shape = if (manga) MaterialTheme.shapes.medium else CircleShape
    Row(
        modifier.fillMaxWidth().height(height).then(if (manga) Modifier.inkPanel(shape, shadow = 3.dp) else Modifier).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (manga) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true, cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp), modifier = Modifier.fillMaxWidth().testTag("search-field"),
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).clickable { onChange("") }.padding(4.dp))
    }
}
