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
    val inset = LocalNavInset.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = inset + 16.dp, end = 40.dp, top = 36.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageTitle("Library")
            if (titles.isNotEmpty()) Text("${titles.size} titles", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.weight(1f))
            Show.entries.forEach { s -> FilterPill(s.label, show == s) { show = s } }
            SearchField(query, { query = it }, "Search your library", Modifier.width(320.dp), height = 46.dp)
        }
        when {
            titles.isEmpty() -> EmptyNote(
                if (indexing) "Reading your chats…" else "Your library is empty",
                if (indexing) "Titles appear here as they are found." else "Choose the Telegram channels or groups that hold your videos.",
            ) { if (!indexing) PillButton("Choose chats", onAddChats) }
            shown.isEmpty() -> EmptyNote("Nothing matches", "Nothing in your library matches \"$query\".")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(168.dp),
                contentPadding = PaddingValues(start = inset + 16.dp, end = 40.dp, top = 12.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.key }) { t -> PosterCard(t, posters) { onOpen(t) } }
            }
        }
    }
}

/** A filter choice: a pill, filled when chosen. */
@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val manga = LocalMangaStyle.current
    val shape = if (manga) MaterialTheme.shapes.small else RoundedCornerShape(50)
    Text(
        text, fontWeight = FontWeight.SemiBold,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.then(if (manga && selected) Modifier.inkPanel(shape, shadow = 2.dp) else Modifier).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
fun PosterCard(t: LibraryTitle, posters: PosterService, onClick: () -> Unit) = TelegramCard(t, posters, onClick = onClick)

/** The title's poster, looked up and loaded in the background (null until then, or when none). */
@Composable
fun posterImage(t: LibraryTitle, posters: PosterService, backdrop: Boolean = false) = produceState<ImageBitmap?>(null, t.key, backdrop) {
    val art = posters.artwork(t)
    value = posters.image(if (backdrop) art.backdrop.ifEmpty { art.poster } else art.poster)
}
