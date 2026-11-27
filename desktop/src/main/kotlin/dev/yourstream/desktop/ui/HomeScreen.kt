package dev.yourstream.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yourstream.catalog.api.CatalogDescriptor
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.core.common.AppResult
import dev.yourstream.desktop.addons.AddonService
import dev.yourstream.desktop.data.LibraryTitle
import dev.yourstream.desktop.data.MediaRecord
import dev.yourstream.desktop.data.resolutionLabel
import dev.yourstream.desktop.library.PosterService

/** Catalog pages Home has loaded, kept while the app runs so going back to Home does not reload them. */
class HomeCache {
    val pages = mutableStateMapOf<String, List<CatalogItem>>()
}

/** A catalog's key in [HomeCache] and in "See all". */
fun CatalogDescriptor.key(): String = "${provider.value}/${type.name}/$id"

/** "Popular · Movies". */
fun CatalogDescriptor.rowTitle(): String = "$name · " + if (type == CatalogType.SERIES) "Series" else "Movies"

/** Titles compared by name: lower case, letters and digits only. */
fun titleKey(title: String): String = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** The library's titles matching an add-on's title (same name, same kind, year within one). */
fun libraryMatches(library: List<LibraryTitle>, title: String, year: Int?, isSeries: Boolean): List<LibraryTitle> {
    val key = titleKey(title)
    return library.filter { t ->
        t.isSeries == isSeries && titleKey(t.title) == key &&
            (isSeries || t.year == null || year == null || kotlin.math.abs(t.year - year) <= 1)
    }
}

/**
 * Home, like the Android app's (and Nuvio's): the hero across the top, then rows: what you were
 * watching, what was added to your Telegram chats, then each add-on catalog ("Popular · Movies"),
 * each with "See all". Your Telegram titles carry a "Telegram" badge in the add-on rows too.
 */
@Composable
fun HomeScreen(
    titles: List<LibraryTitle>, positions: Map<String, Long>, service: AddonService, posters: PosterService, cache: HomeCache,
    indexing: Boolean,
    onOpenTitle: (LibraryTitle) -> Unit, onPlay: (LibraryTitle, MediaRecord) -> Unit, onOpenItem: (CatalogItem) -> Unit,
    onSeeAll: (CatalogDescriptor) -> Unit, onSeeLibrary: () -> Unit, onAddChats: () -> Unit,
) {
    val addons by service.addons.collectAsState()
    val catalogs by produceState(emptyList<CatalogDescriptor>(), addons) { value = service.catalogs().take(MAX_ROWS) }
    // Each catalog's first page, loaded once.
    catalogs.forEach { c ->
        LaunchedEffect(c.key()) {
            if (cache.pages[c.key()] == null) cache.pages[c.key()] = (service.page(c) as? AppResult.Ok)?.value?.items.orEmpty()
        }
    }
    val recent = remember(titles) { titles.sortedByDescending { it.addedAt }.take(40) }
    // Continue watching: titles with a saved position, the latest first (positions keep their order).
    val watching = remember(titles, positions) {
        val byFile = titles.flatMap { t -> t.files.map { it.remoteFileId to (t to it) } }.toMap()
        positions.keys.reversed().mapNotNull { byFile[it] }.distinctBy { it.first.key }.take(20)
    }
    val slides = heroSlides(catalogs, cache, titles, recent, posters, onOpenTitle, onPlay, onOpenItem)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * 0.7f).coerceIn(420.dp, 760.dp)
        LazyColumn(Modifier.fillMaxSize().testTag("home"), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            item(key = "hero") {
                when {
                    slides.isNotEmpty() -> HeroCarousel(slides, heroHeight)
                    titles.isEmpty() && catalogs.isEmpty() -> Welcome(indexing, heroHeight, onAddChats)
                    else -> Backdrop(null, Modifier.fillMaxWidth().height(heroHeight))
                }
            }
            if (watching.isNotEmpty()) item(key = "watching") {
                CardRow("Continue watching", watching.size, onSeeAll = null) {
                    items(watching, key = { it.second.remoteFileId }) { (t, f) ->
                        TelegramCard(t, posters, Modifier.width(CARD), subtitle = resumeLabel(t, f, positions[f.remoteFileId] ?: 0)) { onPlay(t, f) }
                    }
                }
            }
            if (recent.isNotEmpty()) item(key = "recent") {
                CardRow("Recently added · Telegram", recent.size, onSeeAll = onSeeLibrary) {
                    items(recent, key = { it.key }) { t -> TelegramCard(t, posters, Modifier.width(CARD)) { onOpenTitle(t) } }
                }
            } else if (catalogs.isNotEmpty() && !indexing) item(key = "no-chats") { NoChatsNote(onAddChats) }
            items(catalogs, key = { it.key() }) { c ->
                val page = cache.pages[c.key()]
                if (page == null || page.isNotEmpty()) CardRow(c.rowTitle(), page?.size ?: 0, onSeeAll = { onSeeAll(c) }) {
                    if (page == null) items(8) { SkeletonCard(Modifier.width(CARD)) }
                    else items(page, key = { it.ref.provider.value + it.ref.id }) { item ->
                        CatalogItemCard(item, posters, owned = libraryMatches(titles, item.title, item.year, item.ref.type == CatalogType.SERIES).isNotEmpty(), Modifier.width(CARD)) { onOpenItem(item) }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** The hero's slides: the first add-on catalog's titles with artwork, else your latest Telegram titles. */
@Composable
private fun heroSlides(
    catalogs: List<CatalogDescriptor>, cache: HomeCache, titles: List<LibraryTitle>, recent: List<LibraryTitle>, posters: PosterService,
    onOpenTitle: (LibraryTitle) -> Unit, onPlay: (LibraryTitle, MediaRecord) -> Unit, onOpenItem: (CatalogItem) -> Unit,
): List<HeroSlide> {
    val featured = catalogs.firstNotNullOfOrNull { c -> cache.pages[c.key()]?.filter { it.backdropUrl != null }?.takeIf { it.isNotEmpty() } }.orEmpty()
    return remember(featured, recent, titles) {
        if (featured.isNotEmpty()) featured.take(HERO_SLIDES).map { item ->
            val isSeries = item.ref.type == CatalogType.SERIES
            val own = libraryMatches(titles, item.title, item.year, isSeries).firstOrNull()
            HeroSlide(
                key = item.ref.provider.value + item.ref.id, title = item.title, kind = if (isSeries) "Series" else "Movie",
                backdrop = { item.backdropUrl?.let { posters.image(it) } },
                year = item.year, genres = item.genres, runtimeMinutes = item.runtimeMinutes, rating = item.rating, description = item.description,
                telegram = own != null,
                onPlay = own?.takeIf { !it.isSeries }?.let { t -> { onPlay(t, t.files.first()) } },
                onDetails = { onOpenItem(item) },
            )
        } else recent.take(HERO_SLIDES).map { t ->
            HeroSlide(
                key = t.key, title = t.title, kind = if (t.isSeries) "Series" else "Movie",
                backdrop = { posters.artwork(t).let { a -> posters.image(a.backdrop.ifEmpty { a.poster }) } },
                year = t.year, rating = posters.cached(t)?.rating, description = posters.cached(t)?.overview, telegram = true,
                onPlay = if (t.isSeries) null else ({ onPlay(t, t.files.first()) }),
                onDetails = { onOpenTitle(t) },
            )
        }
    }
}

/** First start, nothing to show yet: what to do. */
@Composable
private fun Welcome(indexing: Boolean, height: androidx.compose.ui.unit.Dp, onAddChats: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Backdrop(null, Modifier.fillMaxSize(), seed = 7)
        Column(Modifier.align(Alignment.CenterStart).padding(start = LocalNavInset.current + 16.dp).widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Heading(if (indexing) "Reading your chats…" else "Welcome to YourStream", 48.sp, color = Color.White, maxLines = 2)
            Text(
                if (indexing) "Your titles appear here as they are found." else "Choose the Telegram channels or groups that hold your videos, and add Stremio or Nuvio add-ons for catalogs and streams.",
                color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp,
            )
            if (!indexing) PillButton("Choose chats", onAddChats, icon = Icons.Default.Forum)
        }
    }
}

@Composable
private fun NoChatsNote(onAddChats: () -> Unit) {
    Column(Modifier.padding(start = LocalNavInset.current + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your Telegram titles show here once you choose your chats.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PillButton("Choose chats", onAddChats, primary = false, icon = Icons.Default.Forum)
    }
}

/** "Resume · S1 E2 · 42:10". */
private fun resumeLabel(t: LibraryTitle, f: MediaRecord, ms: Long): String {
    val s = ms / 1000
    val at = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    val ep = if (t.isSeries && f.episode != null) "S${f.season ?: 1} E${f.episode} · " else ""
    return "Resume · $ep$at"
}

/** A Telegram title's card: its poster (looked up in the background), resolution and episode badges. */
@Composable
fun TelegramCard(t: LibraryTitle, posters: PosterService, modifier: Modifier = Modifier, subtitle: String? = t.year?.toString(), onClick: () -> Unit) {
    val image by posterImage(t, posters)
    ArtCard(t.title, subtitle, image, onClick, modifier) {
        resolutionLabel(t.bestResolution)?.let { Chip(it, Color.White, Color.Black) }
        if (t.isSeries) Chip("${t.files.size} ep", Color.Black.copy(alpha = 0.7f), Color.White)
    }
}

/** An add-on title's card: its poster, rating, and "Telegram" when your library has it. */
@Composable
fun CatalogItemCard(item: CatalogItem, posters: PosterService, owned: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, item.posterUrl) { value = item.posterUrl?.let { posters.image(it) } }
    ArtCard(item.title, item.year?.toString(), image, onClick, modifier) {
        if (owned) Chip("Telegram", Color.Black.copy(alpha = 0.72f), Color.White)
        item.rating?.takeIf { it > 0 }?.let { Chip("★ %.1f".format(java.util.Locale.ROOT, it), ImdbYellow, Color.Black) }
    }
}

/** A card's space while its catalog loads. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.medium else RoundedCornerShape(14.dp)
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer))
        Box(Modifier.padding(top = 10.dp).width(110.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer))
    }
}

/** An empty page's message, centred. */
@Composable
fun EmptyNote(title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(start = LocalNavInset.current, end = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
            action?.invoke()
        }
    }
}

private val CARD = 168.dp
private const val MAX_ROWS = 12
private const val HERO_SLIDES = 6
