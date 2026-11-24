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

/** Installed Stremio / Nuvio add-ons: add by link, order (which orders Discover and streams), remove. */
@Composable
fun AddonsScreen(service: AddonService) {
    val addons by service.addons.collectAsState()
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { service.refresh() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Heading("Add-ons", 26.sp)
            Text(
                "Stremio and Nuvio add-ons add catalogs to Discover and streams to titles. Paste an add-on's link (it ends in manifest.json).",
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
fun DiscoverScreen(service: AddonService, posters: PosterService, onOpen: (CatalogItem) -> Unit) {
    var type by rememberSaveable { mutableStateOf(CatalogType.MOVIE) }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogId by rememberSaveable { mutableStateOf<String?>(null) }
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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Heading("Discover", 26.sp)
            Box(Modifier.weight(1f))
            FilterChip(type == CatalogType.MOVIE, { type = CatalogType.MOVIE; catalogId = null }, label = { Text("Movies") })
            FilterChip(type == CatalogType.SERIES, { type = CatalogType.SERIES; catalogId = null }, label = { Text("Series") })
            CatalogPicker(catalogs, catalog, addons.associate { a -> (a.info?.provider?.value ?: "") to a.name }) { catalogId = "${it.provider.value}/${it.id}" }
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search add-ons") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.width(280.dp))
        }
        when {
            catalogs.isEmpty() && addons.isEmpty() -> Centered("No add-ons installed. Add one in Add-ons.")
            items.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Centered(error ?: if (query.isNotBlank()) "Nothing found for \"$query\"" else "This catalog is empty.")
            else -> LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(items, key = { it.ref.provider.value + it.ref.id }) { item -> CatalogCard(item, posters) { onOpen(item) } }
                if (next != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator() else OutlinedButton(onClick = { scope.launch { load(next ?: return@launch) } }) { Text("Load more") }
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

@Composable
private fun CatalogCard(item: CatalogItem, posters: PosterService, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, item.posterUrl) { value = item.posterUrl?.let { posters.image(it) } }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.medium else RoundedCornerShape(10.dp)
    Column(Modifier.hoverable(hover).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).scale(if (hovered) 1.03f else 1f).inkPanel(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val img = image
            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (LocalMangaStyle.current) MangaTitleArt(item.title, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) { Text(item.title, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
            item.rating?.let { Badge("★ %.1f".format(it), Modifier.align(Alignment.TopStart).padding(6.dp), color = Color(0xFFF5C518), onColor = Color.Black) }
        }
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        item.year?.let { Text(it.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ---------------------------------------------------------------- An add-on title

/**
 * A title from an add-on: details, seasons and episodes, then where to watch it: the user's own
 * Telegram files first (when the library has the title), then each add-on's streams as they come.
 * Torrent streams are listed but not played in the Windows app yet.
 */
@Composable
fun CatalogTitleScreen(
    item: CatalogItem, service: AddonService, posters: PosterService, library: List<LibraryTitle>,
    onBack: () -> Unit, onPlayLibrary: (LibraryTitle) -> Unit, onPlayStream: (CatalogMetadata, CatalogVideo?, AddonStream) -> Unit,
) {
    val meta by produceState<CatalogMetadata?>(null, item.ref) { value = runCatching { service.metadata(item) }.getOrNull() }
    val m = meta
    val backdrop by produceState<ImageBitmap?>(null, m?.backdropUrl ?: item.backdropUrl) { value = (m?.backdropUrl ?: item.backdropUrl)?.let { posters.image(it) } }
    val poster by produceState<ImageBitmap?>(null, m?.posterUrl ?: item.posterUrl) { value = (m?.posterUrl ?: item.posterUrl)?.let { posters.image(it) } }
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
    val owned = remember(library, item) {
        val key = item.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        library.filter { t -> t.isSeries == isSeries && t.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() == key && (isSeries || t.year == null || item.year == null || kotlin.math.abs(t.year - item.year!!) <= 1) }
    }

    Box(Modifier.fillMaxSize()) {
        backdrop?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(420.dp))
            Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        LazyColumn(Modifier.fillMaxSize().testTag("title-page"), contentPadding = PaddingValues(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.height(100.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.medium else RoundedCornerShape(12.dp)
                    Box(Modifier.width(200.dp).height(300.dp).inkPanel(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        poster?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: MangaTitleArt(item.title, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(top = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Heading(m?.title ?: item.title, 38.sp, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            (m?.year ?: item.year)?.let { Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            (m?.runtimeMinutes ?: item.runtimeMinutes)?.let { Text("${it / 60}h ${it % 60}m".removePrefix("0h "), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            (m?.rating ?: item.rating)?.let { Badge("★ %.1f".format(it), color = Color(0xFFF5C518), onColor = Color.Black) }
                            if (owned.isNotEmpty()) Badge("Telegram", color = Color(0xFF2AABEE), onColor = Color.White)
                        }
                        val genres = (m?.genres ?: item.genres)
                        if (genres.isNotEmpty()) Text(genres.joinToString(" • "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        (m?.description ?: item.description)?.let { Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp)) }
                        val cast = m?.cast.orEmpty()
                        if (cast.isNotEmpty()) Text("Cast: " + cast.take(6).joinToString(", "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            if (isSeries) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.keys.forEach { s -> FilterChip(s == season, { season = s; episode = null }, label = { Text("Season $s") }) }
                    }
                }
                items(seasons[season].orEmpty(), key = { it.id }) { v ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (v == episode) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { episode = v }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${v.episode ?: "?"}", fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.title ?: "Episode ${v.episode}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            v.overview?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            if (owned.isNotEmpty()) {
                item { Heading("Your Telegram files", 20.sp, Modifier.padding(top = 12.dp)) }
                items(owned, key = { "own:" + it.key }) { t -> StreamRow(t.title, "${t.files.size} file(s) · ${resolutionLabel(t.bestResolution) ?: ""}", "Telegram") { onPlayLibrary(t) } }
            }
            if (streamId != null) {
                item { Heading("Streams", 20.sp, Modifier.padding(top = 12.dp)) }
                groups.forEach { g ->
                    item(key = "g:" + g.addon) {
                        Text(g.addon + (g.error?.let { " — not answering" } ?: if (g.streams.isEmpty()) " — nothing found" else ""), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(g.streams.withIndex().toList(), key = { "s:" + g.addon + it.index }) { (_, s) ->
                        val label = s.name.replace('\n', ' ')
                        when {
                            s.playable -> StreamRow(label, listOfNotNull(s.description?.replace('\n', ' '), s.sizeBytes?.let { sizeLabel(it) }).joinToString(" · "), g.addon) {
                                onPlayStream(m ?: CatalogMetadata(item.ref, item.title, item.year), episode, s)
                            }
                            s.isTorrent -> StreamRow(label, (s.description?.replace('\n', ' ') ?: "") + " · torrents are not in the Windows app yet", g.addon, enabled = false) {}
                            else -> Unit
                        }
                    }
                }
                if (waiting) item { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Text("  Asking the add-ons…") } }
            } else if (isSeries) item { Text("Pick an episode to see its streams.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@Composable
private fun StreamRow(title: String, detail: String, source: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(source, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
