package dev.yourstream.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.data.resolutionLabel
import dev.yourstream.desktop.data.sizeLabel
import dev.yourstream.desktop.library.Artwork
import dev.yourstream.desktop.library.PosterService

/** A title's page: artwork, overview and Play; for a series, its seasons and episodes. */
@Composable
fun TitleScreen(t: LibraryTitle, posters: PosterService, resumeMs: (MediaRecord) -> Long, onBack: () -> Unit, onPlay: (MediaRecord, List<MediaRecord>) -> Unit) {
    val art by produceState(posters.cached(t) ?: Artwork(), t.key) { value = posters.artwork(t) }
    val backdrop by posterImage(t, posters, backdrop = true)
    val poster by posterImage(t, posters)
    Box(Modifier.fillMaxSize()) {
        if (LocalMangaStyle.current && backdrop == null) Box(Modifier.fillMaxWidth().height(420.dp).clipToBounds().speedLines(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), seed = t.key.hashCode()))
        backdrop?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(420.dp))
            Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(32.dp)) {
            item {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.height(120.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.medium else RoundedCornerShape(12.dp)
                    Box(Modifier.width(200.dp).height(300.dp).inkPanel(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        poster?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            ?: if (LocalMangaStyle.current) MangaTitleArt(t.title, Modifier.fillMaxSize()) else Unit
                    }
                    Column(Modifier.weight(1f).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Heading(t.title, 40.sp, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            t.year?.let { Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            resolutionLabel(t.bestResolution)?.let { Badge(it) }
                            art.rating?.let { Badge("★ %.1f".format(it), color = Color(0xFFF5C518), onColor = Color.Black) }
                            Badge("Telegram", color = Color(0xFF2AABEE), onColor = Color.White)
                        }
                        if (art.overview.isNotBlank()) Text(art.overview, modifier = Modifier.widthIn(max = 760.dp), maxLines = 5, overflow = TextOverflow.Ellipsis)
                        if (!t.isSeries) {
                            val best = t.files.first()
                            Button(onClick = { onPlay(best, listOf(best)) }, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text(if (resumeMs(best) > 0) "Resume" else "Play", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
            if (t.isSeries) item { Episodes(t, resumeMs, onPlay) }
            else {
                item { Heading("Versions", 20.sp, Modifier.padding(bottom = 8.dp)) }
                items(t.files, key = { "${it.chatId}:${it.messageId}" }) { f -> VersionRow(f, resumeMs(f)) { onPlay(f, listOf(f)) } }
            }
        }
    }
}

@Composable
private fun Episodes(t: LibraryTitle, resumeMs: (MediaRecord) -> Long, onPlay: (MediaRecord, List<MediaRecord>) -> Unit) {
    val seasons = remember(t) { t.seasons }
    var season by remember(t.key) { mutableStateOf(seasons.keys.first()) }
    val episodes = seasons[season].orEmpty()
    // Next-up order: the best version of each episode of this season.
    val queue = remember(episodes) { episodes.values.map { it.first() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.keys.forEach { s -> FilterChip(selected = s == season, onClick = { season = s }, label = { Text(if (s == 0) "Episodes" else "Season $s") }) }
        }
        episodes.forEach { (number, versions) ->
            val best = versions.first()
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().clickable { onPlay(best, queue.dropWhile { it != best }) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (number == 0) best.fileName else "Episode $number", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(details(best) + if (versions.size > 1) " · ${versions.size} versions" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (resumeMs(best) > 0) Badge("Resume")
                }
            }
        }
    }
}

@Composable
private fun VersionRow(f: MediaRecord, resume: Long, onPlay: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.PlayArrow, null)
            Column(Modifier.weight(1f)) {
                Text(f.fileName.ifBlank { "Video" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(details(f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (resume > 0) Badge("Resume")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun details(f: MediaRecord): String = listOfNotNull(
    resolutionLabel(f.resolution), f.hdr, f.videoCodec?.uppercase(), f.quality,
    f.languages.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() },
    sizeLabel(f.sizeBytes),
).joinToString(" · ")
