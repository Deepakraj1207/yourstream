package dev.yourstream.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.desktop.data.LibraryBuilder
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.data.resolutionLabel
import dev.yourstream.desktop.library.PosterService

private enum class Show(val label: String) { ALL("All"), MOVIES("Movies"), SERIES("Series") }

/** Every title from the chosen chats, newest first, with search and a movies/series filter. */
@Composable
fun LibraryScreen(titles: List<LibraryTitle>, posters: PosterService, indexing: Boolean, onOpen: (LibraryTitle) -> Unit, onAddChats: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(Show.ALL) }
    val shown = remember(titles, query, show) {
        LibraryBuilder.search(titles, query).filter {
            when (show) { Show.ALL -> true; Show.MOVIES -> !it.isSeries; Show.SERIES -> it.isSeries }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f))
            Show.entries.forEach { s -> FilterChip(selected = show == s, onClick = { show = s }, label = { Text(s.label) }) }
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("Search your library") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.width(300.dp),
            )
        }
        when {
            titles.isEmpty() -> Empty(indexing, onAddChats)
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing matches \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.key }) { t -> PosterCard(t, posters) { onOpen(t) } }
            }
        }
    }
}

@Composable
private fun Empty(indexing: Boolean, onAddChats: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (indexing) "Reading your chats…" else "Your library is empty", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (indexing) "Titles appear here as they are found." else "Choose the Telegram channels or groups that hold your videos.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            if (!indexing) androidx.compose.material3.Button(onClick = onAddChats) { Text("Choose chats") }
        }
    }
}

@Composable
fun PosterCard(t: LibraryTitle, posters: PosterService, onClick: () -> Unit) {
    val image by posterImage(t, posters)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.hoverable(hover).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).scale(if (hovered) 1.03f else 1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val img = image
            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Text(t.title, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                resolutionLabel(t.bestResolution)?.let { Badge(it) }
                if (t.isSeries) Badge("${t.files.size} ep", color = MaterialTheme.colorScheme.surfaceContainerHighest, onColor = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        t.year?.let { Text(it.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The title's poster, looked up and loaded in the background (null until then, or when none). */
@Composable
fun posterImage(t: LibraryTitle, posters: PosterService, backdrop: Boolean = false) = produceState<ImageBitmap?>(null, t.key, backdrop) {
    val art = posters.artwork(t)
    value = posters.image(if (backdrop) art.backdrop.ifEmpty { art.poster } else art.poster)
}
