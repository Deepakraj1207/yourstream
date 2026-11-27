package dev.yourstream.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.CatalogVideo
import dev.yourstream.core.common.AppResult
import dev.yourstream.desktop.addons.AddonService
import dev.yourstream.desktop.addons.StreamGroup
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.data.resolutionLabel
import dev.yourstream.desktop.data.sizeLabel
import dev.yourstream.desktop.library.PosterService
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Add-ons

/** Installed Stremio / Nuvio add-ons (Settings → Add-ons): add by link, order (which orders Home's rows and the streams), remove. */
@Composable
fun AddonsScreen(service: AddonService) {
    val addons by service.addons.collectAsState()
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { service.refresh() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(end = 40.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Heading("Add-ons", 26.sp)
            Text(
                "Stremio and Nuvio add-ons add catalog rows to Home and streams to title pages. Paste an add-on's link (it ends in manifest.json).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(link, { link = it; message = null }, singleLine = true, placeholder = { Text("https://…/manifest.json") }, modifier = Modifier.weight(1f).testTag("addon-link"))
                Button(onClick = {
                    busy = true
                    scope.launch {
                        message = when (val r = service.install(link)) {
                            is AppResult.Ok -> { link = ""; "Installed ${r.value.name}." }
                            is AppResult.Err -> r.error.message
                        }
                        busy = false
                    }
                }, enabled = !busy && link.isNotBlank()) { Text(if (busy) "Installing…" else "Install") }
            }
            message?.let { Text(it, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(addons, key = { it.url }) { a ->
            val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.medium else RoundedCornerShape(12.dp)
            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().inkPanel(shape)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(a.name + (a.info?.version?.let { "  v$it" } ?: ""), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        a.info?.description?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        val offers = listOfNotNull(
                            a.info?.catalogs?.size?.takeIf { it > 0 }?.let { "$it catalogs" },
                            "Streams".takeIf { a.info?.supportsStreams == true },
                            "Details".takeIf { a.info?.supportsMeta == true },
                        )
                        Text(a.error?.let { "Not answering: $it" } ?: offers.joinToString(" · ").ifEmpty { "…" }, fontSize = 12.sp, color = if (a.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { service.move(a.url, -1) }) { Icon(Icons.Default.ArrowUpward, "Move up") }
                    IconButton(onClick = { service.move(a.url, 1) }) { Icon(Icons.Default.ArrowDownward, "Move down") }
                    IconButton(onClick = { service.remove(a.url) }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Discover

/** The add-ons' catalogs as poster grids (Movies / Series, one catalog at a time), and search. */
@Composable
fun DiscoverScreen(
    service: AddonService, posters: PosterService, initial: CatalogDescriptor? = null, library: List<LibraryTitle> = emptyList(),
    onBack: (() -> Unit)? = null, onOpen: (CatalogItem) -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(initial?.type ?: CatalogType.MOVIE) }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogId by rememberSaveable { mutableStateOf(initial?.let { "${it.provider.value}/${it.id}" }) }
    val addons by service.addons.collectAsState()
    val catalogs by produceState(emptyList<CatalogDescriptor>(), type, addons) { value = service.catalogs(type) }
    val catalog = catalogs.firstOrNull { "${it.provider.value}/${it.id}" == catalogId } ?: catalogs.firstOrNull()
    val items = remember { mutableStateListOf<CatalogItem>() }
    var next by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(skip: Int) {
        val c = catalog ?: return
        loading = true
        when (val r = service.page(c, skip)) {
            is AppResult.Ok -> { if (skip == 0) items.clear(); items += r.value.items.filter { n -> items.none { it.ref == n.ref } }; next = r.value.nextSkip; error = null }
            is AppResult.Err -> error = r.error.message
        }
        loading = false
    }
    LaunchedEffect(catalog, query) {
        if (query.isNotBlank()) {
            kotlinx.coroutines.delay(400)
            loading = true
            val found = service.search(query.trim(), type)
            items.clear(); items += found; next = null; loading = false
        } else {
            items.clear(); load(0)
        }
    }

    val inset = LocalNavInset.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = inset + 16.dp, end = 40.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onBack != null) RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            PageTitle(catalog?.rowTitle() ?: "Discover", Modifier.weight(1f, fill = false))
            Box(Modifier.weight(1f))
            FilterPill("Movies", type == CatalogType.MOVIE) { type = CatalogType.MOVIE; catalogId = null }
            FilterPill("Series", type == CatalogType.SERIES) { type = CatalogType.SERIES; catalogId = null }
            CatalogPicker(catalogs, catalog, addons.associate { a -> (a.info?.provider?.value ?: "") to a.name }) { catalogId = "${it.provider.value}/${it.id}" }
            SearchField(query, { query = it }, "Search add-ons", Modifier.width(280.dp), height = 46.dp)
        }
        when {
            catalogs.isEmpty() && addons.isEmpty() -> Centered("No add-ons installed. Add one in Settings → Add-ons.")
            items.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Centered(error ?: if (query.isNotBlank()) "Nothing found for \"$query\"" else "This catalog is empty.")
            else -> LazyVerticalGrid(GridCells.Adaptive(168.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = inset + 16.dp, end = 40.dp, top = 12.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                items(items, key = { it.ref.provider.value + it.ref.id }) { item ->
                    CatalogItemCard(item, posters, libraryMatches(library, item.title, item.year, item.ref.type == CatalogType.SERIES).isNotEmpty()) { onOpen(item) }
                }
                if (next != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator() else PillButton("Load more", { scope.launch { load(next ?: return@launch) } }, primary = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) = Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun CatalogPicker(catalogs: List<CatalogDescriptor>, selected: CatalogDescriptor?, addonNames: Map<String, String>, onPick: (CatalogDescriptor) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = catalogs.isNotEmpty()) {
            Text(selected?.name ?: "Catalog", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            catalogs.forEach { c ->
                DropdownMenuItem(text = {
                    Column {
                        Text(c.name, fontWeight = if (c == selected) FontWeight.Bold else FontWeight.Normal)
                        addonNames[c.provider.value]?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }, onClick = { onPick(c); open = false })
            }
        }
    }
}

// ---------------------------------------------------------------- An add-on title

/**
 * A title from an add-on, laid out like the Android app's title page (and Nuvio's): the artwork
 * across the window with the title, details and Play over it; for a series, the seasons and the
 * episodes as cards; then where to watch: your Telegram files first (when the library has the
 * title), then each add-on's streams as they come. Torrent streams are listed but not played in
 * the Windows app yet.
 */
@Composable
fun CatalogTitleScreen(
    item: CatalogItem, service: AddonService, posters: PosterService, library: List<LibraryTitle>,
    onBack: () -> Unit, onPlayLibrary: (LibraryTitle) -> Unit, onPlayStream: (CatalogMetadata, CatalogVideo?, AddonStream) -> Unit,
) {
    val meta by produceState<CatalogMetadata?>(null, item.ref) { value = runCatching { service.metadata(item) }.getOrNull() }
    val m = meta
    val art = m?.backdropUrl ?: item.backdropUrl ?: m?.posterUrl ?: item.posterUrl
    val backdrop by produceState<ImageBitmap?>(null, art) { value = art?.let { posters.image(it) } }
    val seasons = remember(m) { m?.videos.orEmpty().filter { (it.season ?: 0) > 0 }.groupBy { it.season!! }.toSortedMap() }
    var season by remember(m) { mutableStateOf(seasons.keys.firstOrNull()) }
    var episode by remember(m) { mutableStateOf<CatalogVideo?>(null) }
    val isSeries = item.ref.type == CatalogType.SERIES
    // Which id the add-ons are asked for: the movie, or the picked episode.
    val streamId = if (isSeries) episode?.id else (item.ref.imdbId ?: item.ref.id)
    val groups = remember(streamId) { mutableStateListOf<StreamGroup>() }
    var waiting by remember(streamId) { mutableStateOf(streamId != null) }
    LaunchedEffect(streamId) {
        if (streamId == null) return@LaunchedEffect
        service.streams(item.ref.type, streamId).collect { groups += it }
        waiting = false
    }
    val owned = remember(library, item) { libraryMatches(library, item.title, item.year, isSeries) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstPlayable = groups.firstNotNullOfOrNull { g -> g.streams.firstOrNull { it.playable } }
    // Where "Sources" scrolls to: after the header, the episodes and your Telegram files.
    val sourcesIndex = 1 + (if (isSeries) 2 else 0) + (if (owned.isNotEmpty()) 1 + owned.size else 0)

    LazyColumn(Modifier.fillMaxSize().testTag("title-page"), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "header") {
            val cast = m?.cast.orEmpty()
            val directors = m?.directors ?: item.directors
            TitleHeader(
                m?.title ?: item.title, backdrop, onBack,
                year = m?.year ?: item.year, runtimeMinutes = m?.runtimeMinutes ?: item.runtimeMinutes, rating = m?.rating ?: item.rating,
                genres = m?.genres ?: item.genres, description = m?.description ?: item.description,
                credits = listOfNotNull(
                    directors.takeIf { it.isNotEmpty() }?.let { "Director: " + it.take(2).joinToString(", ") },
                    cast.takeIf { it.isNotEmpty() }?.let { "Cast: " + it.take(5).joinToString(", ") },
                ).joinToString("   ").ifBlank { null },
                seed = item.title.hashCode(),
                badges = {
                    Chip(if (isSeries) "Series" else "Movie", Color.Black.copy(alpha = 0.6f), Color.White)
                    if (owned.isNotEmpty()) Chip("Telegram", TelegramBlue, Color.White)
                },
                actions = {
                    when {
                        owned.isNotEmpty() && !isSeries -> PillButton("Play from Telegram", { onPlayLibrary(owned.first()) }, icon = Icons.Default.PlayArrow)
                        firstPlayable != null -> PillButton("Play", { onPlayStream(m ?: CatalogMetadata(item.ref, item.title, item.year), episode, firstPlayable) }, icon = Icons.Default.PlayArrow)
                    }
                    if (streamId != null) PillButton("Sources", { scope.launch { list.animateScrollToItem(sourcesIndex) } }, primary = owned.isEmpty() && firstPlayable == null)
                },
            )
        }
        if (isSeries) {
            item(key = "seasons") {
                Row(Modifier.padding(start = LocalNavInset.current + 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    seasons.keys.forEach { s -> FilterPill("Season $s", s == season) { season = s; episode = null } }
                    if (seasons.isEmpty()) Text(if (m == null) "Loading episodes…" else "No episodes listed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "episodes") {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(start = LocalNavInset.current + 16.dp, end = 40.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(season?.let { seasons[it] }.orEmpty(), key = { it.id }) { v -> EpisodeCard(v, v == episode, posters) { episode = v } }
                }
            }
        }
        if (owned.isNotEmpty()) {
            item(key = "own-title") { SectionTitle("Your Telegram files") }
            items(owned, key = { "own:" + it.key }) { t -> SourceCard(t.title, "${t.files.size} file(s) · ${resolutionLabel(t.bestResolution) ?: ""}", "Telegram", { onPlayLibrary(t) }) }
        }
        if (streamId != null) {
            item(key = "streams-title") { SectionTitle("Streams") }
            groups.forEach { g ->
                item(key = "g:" + g.addon) {
                    Text(
                        g.addon + (g.error?.let { " — not answering" } ?: if (g.streams.isEmpty()) " — nothing found" else ""),
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = LocalNavInset.current + 16.dp, top = 8.dp),
                    )
                }
                items(g.streams.withIndex().toList(), key = { "s:" + g.addon + it.index }) { (_, s) ->
                    val label = s.name.replace('\n', ' ')
                    when {
                        s.playable -> SourceCard(label, listOfNotNull(s.description?.replace('\n', ' '), s.sizeBytes?.let { sizeLabel(it) }).joinToString(" · "), g.addon, {
                            onPlayStream(m ?: CatalogMetadata(item.ref, item.title, item.year), episode, s)
                        })
                        s.isTorrent -> SourceCard(label, (s.description?.replace('\n', ' ') ?: "") + " · torrents are not in the Windows app yet", g.addon, {}, enabled = false)
                        else -> Unit
                    }
                }
            }
            if (waiting) item(key = "waiting") {
                Row(Modifier.padding(start = LocalNavInset.current + 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Text("  Asking the add-ons…")
                }
            }
        } else if (isSeries) item(key = "pick") {
            Text("Pick an episode to see its streams.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = LocalNavInset.current + 16.dp, top = 12.dp))
        }
        item(key = "end") { Spacer(Modifier.height(40.dp)) }
    }
}

/** An episode: its still (when the add-on has one), number and name, the overview under it. */
@Composable
private fun EpisodeCard(v: CatalogVideo, selected: Boolean, posters: PosterService, onClick: () -> Unit) {
    val still by produceState<ImageBitmap?>(null, v.thumbnail) { value = v.thumbnail?.let { posters.image(it) } }
    val manga = LocalMangaStyle.current
    val shape = if (manga) MaterialTheme.shapes.medium else RoundedCornerShape(14.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.width(280.dp).hoverable(hover).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).inkPanel(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if ((selected || hovered) && !manga) Modifier.border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.5f), shape) else Modifier),
        ) {
            still?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Chip("E${v.episode ?: "?"}", Color.Black.copy(alpha = 0.7f), Color.White, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (selected) Chip("Showing streams", Color.White, Color.Black, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Text(v.title ?: "Episode ${v.episode}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        v.overview?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** An add-on stream as an item the player can play (its address is the stream's URL). */
fun streamRecord(meta: CatalogMetadata, video: CatalogVideo?, stream: AddonStream): MediaRecord {
    val text = stream.name + " " + (stream.description ?: "") + " " + (stream.fileName ?: "")
    val res = Regex("(?i)(2160|1440|1080|720|576|480)p").find(text)?.groupValues?.get(1)?.toInt() ?: if (Regex("(?i)\\b4k\\b").containsMatchIn(text)) 2160 else null
    return MediaRecord(
        chatId = 0, messageId = 0, messageDate = 0, fileId = -1,
        remoteFileId = "addon:" + (video?.id ?: meta.ref.imdbId ?: meta.ref.id),
        fileName = stream.fileName ?: stream.name.replace('\n', ' '), sizeBytes = stream.sizeBytes ?: 0, video = true,
        title = meta.title, year = meta.year, season = video?.season, episode = video?.episode, resolution = res,
    )
}
