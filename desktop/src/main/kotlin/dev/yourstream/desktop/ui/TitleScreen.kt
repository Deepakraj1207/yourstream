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
import androidx.compose.ui.platform.testTag
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.data.episodeQueue
import dev.yourstream.desktop.data.playFile
import dev.yourstream.desktop.data.preferredVersion
import dev.yourstream.desktop.data.resolutionLabel
import dev.yourstream.desktop.data.sizeLabel
import dev.yourstream.desktop.library.Artwork
import dev.yourstream.desktop.library.PosterService

/**
 * A Telegram title's page, laid out like the Android app's (and Nuvio's): the artwork across the
 * window with the title, details and Play over it; then the seasons and episodes, or the versions.
 */
@Composable
fun TitleScreen(t: LibraryTitle, posters: PosterService, resumeMs: (MediaRecord) -> Long, onBack: () -> Unit, onPlay: (MediaRecord, List<MediaRecord>) -> Unit) {
    val art by produceState(posters.cached(t) ?: Artwork(), t.key) { value = posters.artwork(t) }
    val backdrop by posterImage(t, posters, backdrop = true)
    LazyColumn(Modifier.fillMaxSize().testTag("title-page"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "header") {
            val first = t.playFile()
            TitleHeader(
                t.title, backdrop, onBack, year = t.year, rating = art.rating, description = art.overview, seed = t.key.hashCode(),
                badges = {
                    Chip("Telegram", TelegramBlue, Color.White)
                    resolutionLabel(t.bestResolution)?.let { Chip(it, Color.White, Color.Black) }
                    if (t.isSeries) Chip("${t.seasons.size} season" + if (t.seasons.size == 1) "" else "s", Color.Black.copy(alpha = 0.6f), Color.White)
                },
                actions = {
                    if (first != null) {
                        val resume = resumeMs(first) > 0
                        val queue = if (t.isSeries) episodeQueue(t.seasons.values.first()) else listOf(first)
                        PillButton(if (resume) "Resume" else "Play", { onPlay(first, queue) }, icon = Icons.Default.PlayArrow)
                    }
                },
            )
        }
        if (t.isSeries) item(key = "episodes") { Episodes(t, resumeMs, onPlay) }
        else {
            item(key = "versions-title") { SectionTitle("Versions") }
            items(t.files, key = { "${it.chatId}:${it.messageId}" }) { f ->
                SourceCard(f.fileName.ifBlank { "Video" }, details(f), "Telegram", { onPlay(f, listOf(f)) }) {
                    if (resumeMs(f) > 0) Chip("Resume", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun Episodes(t: LibraryTitle, resumeMs: (MediaRecord) -> Long, onPlay: (MediaRecord, List<MediaRecord>) -> Unit) {
    val seasons = remember(t) { t.seasons }
    var season by remember(t.key) { mutableStateOf(seasons.keys.first()) }
    val episodes = seasons[season].orEmpty()
    // Next-up order: the best version of each episode of this season.
    val queue = remember(episodes) { episodeQueue(episodes) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start = LocalNavInset.current + 16.dp, top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.keys.forEach { s -> FilterPill(if (s == 0) "Episodes" else "Season $s", s == season) { season = s } }
        }
        episodes.forEach { (number, versions) ->
            val best = preferredVersion(versions)
            SourceCard(
                if (number == 0) best.fileName else "Episode $number",
                details(best) + if (versions.size > 1) " · ${versions.size} versions" else "",
                "", { onPlay(best, queue.dropWhile { it != best }) },
                leading = { Text(if (number == 0) "▶" else "$number", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            ) { if (resumeMs(best) > 0) Chip("Resume", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

private fun details(f: MediaRecord): String = listOfNotNull(
    resolutionLabel(f.resolution), f.hdr, f.videoCodec?.uppercase(), f.quality,
    f.languages.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() },
    sizeLabel(f.sizeBytes),
).joinToString(" · ")
