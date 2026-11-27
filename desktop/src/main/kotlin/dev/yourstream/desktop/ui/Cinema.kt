package dev.yourstream.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The desktop app's look, after the Android app's Nuvio-style screens: full-bleed artwork that
 * fades into a black page, a floating glass sidebar, rows of rounded posters that lift on hover,
 * white pill buttons. Every piece also has a manga form (inked panels, square corners).
 */

/** How far page content starts from the window's left edge: the floating sidebar's room. */
val LocalNavInset = staticCompositionLocalOf { 0.dp }

/** A translucent panel over the page (Skia cannot blur what is behind, so a dark tint and a hairline). */
fun Modifier.glass(shape: Shape): Modifier = composed {
    if (LocalMangaStyle.current) this.inkPanel(shape).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
    else this.clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f))
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
}

/** One destination in the sidebar. */
data class NavDestination(val label: String, val icon: ImageVector)

/**
 * The floating sidebar: a glass pill on the left, an icon per destination, the current one in a
 * white circle (an ink square in the manga style), the name in a tooltip on hover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SideBar(destinations: List<NavDestination>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val manga = LocalMangaStyle.current
    val shape = if (manga) MaterialTheme.shapes.large else RoundedCornerShape(36.dp)
    Column(
        modifier.width(68.dp).glass(shape).padding(vertical = 14.dp).testTag("sidebar"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Heading("YS", 20.sp, Modifier.padding(bottom = 6.dp))
        destinations.forEachIndexed { i, d ->
            TooltipArea(
                tooltip = { Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface) { Text(d.label, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) } },
                delayMillis = 300, tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(8.dp, 0.dp)),
            ) {
                val on = i == selected
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                val itemShape = if (manga) MaterialTheme.shapes.small else CircleShape
                val fill = when {
                    on -> MaterialTheme.colorScheme.primary
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
                Box(
                    Modifier.size(48.dp).clip(itemShape).background(fill).hoverable(hover).clickable { onSelect(i) }.testTag("nav:${d.label}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(d.icon, d.label, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
                }
            }
        }
    }
}

/** The white pill: the page's main action ("Play", "View details"). */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = true) {
    val manga = LocalMangaStyle.current
    val shape = if (manga) MaterialTheme.shapes.small else CircleShape
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        primary -> Color.White
        manga -> if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (hovered) 0.22f else 0.14f)
    }
    val fg = if (primary) Color.Black else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.heightIn(min = 48.dp).then(if (manga) Modifier.inkPanel(shape) else Modifier).clip(shape)
            .background(if (primary && hovered) Color(0xFFE6E6E6) else bg).hoverable(hover).clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(22.dp)) }
        Text(if (manga) text.uppercase() else text, color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** A round glass button with an icon (back, more, next slide). */
@Composable
fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.small else CircleShape
    Box(modifier.size(size).glass(shape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** A small rounded label, e.g. "★ 7.4", "4K", "Telegram". */
@Composable
fun Chip(text: String, background: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = content, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

val ImdbYellow = Color(0xFFF5C518)
val TelegramBlue = Color(0xFF2AABEE)

/** The poster card in rows and grids: rounded artwork that lifts and gets a white edge on hover, the title and year under it. */
@Composable
fun ArtCard(
    title: String, subtitle: String?, image: ImageBitmap?, onClick: () -> Unit, modifier: Modifier = Modifier,
    badges: @Composable RowScope.() -> Unit = {},
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val manga = LocalMangaStyle.current
    val shape = if (manga) MaterialTheme.shapes.medium else RoundedCornerShape(14.dp)
    val lift by animateFloatAsState(if (hovered) 1.04f else 1f, tween(160))
    Column(modifier.hoverable(hover).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).scale(lift).inkPanel(shape).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (hovered && !manga) Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), shape) else Modifier),
        ) {
            if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else if (manga) MangaTitleArt(title, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                Text(title, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = badges)
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        subtitle?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

/**
 * A titled row of cards that scrolls sideways: arrows at its ends on hover (and Shift + wheel),
 * "See all" on the right when there is more.
 */
@Composable
fun CardRow(title: String, count: Int, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier, cardWidth: Dp = 168.dp, content: LazyListScope.() -> Unit) {
    val inset = LocalNavInset.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(modifier.fillMaxWidth().hoverable(hover).testTag("row:$title")) {
        Row(Modifier.fillMaxWidth().padding(start = inset + 16.dp, end = 40.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading(title, 22.sp, if (LocalMangaStyle.current) Modifier.inkUnderline(MaterialTheme.colorScheme.onSurface, fraction = 1f) else Modifier)
            Spring()
            if (onSeeAll != null) Text(
                "See all ›", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box {
            LazyRow(state = state, contentPadding = PaddingValues(start = inset + 16.dp, end = 40.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), content = content)
            if (hovered && count > 0) {
                val step = with(androidx.compose.ui.platform.LocalDensity.current) { (cardWidth * 4).toPx() }
                if (state.canScrollBackward) RoundButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", { scope.launch { state.animateScrollBy(-step) } },
                    Modifier.align(Alignment.CenterStart).padding(start = inset + 16.dp, bottom = 40.dp))
                if (state.canScrollForward) RoundButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "More", { scope.launch { state.animateScrollBy(step) } },
                    Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 40.dp))
            }
        }
    }
}

/** One slide of the home page's hero. */
data class HeroSlide(
    val key: String, val title: String, val kind: String, val backdrop: suspend () -> ImageBitmap?,
    val year: Int? = null, val genres: List<String> = emptyList(), val runtimeMinutes: Int? = null, val rating: Double? = null,
    val description: String? = null, val telegram: Boolean = false,
    val playLabel: String? = null, val onPlay: (() -> Unit)? = null, val onDetails: () -> Unit,
)

/**
 * The home page's hero: a title's artwork across the whole window, fading into the page from the
 * left and the bottom, with its name, details and actions; the next slide every 8 s (not while the
 * pointer is on it), arrows and dots to move by hand.
 */
@Composable
fun HeroCarousel(slides: List<HeroSlide>, height: Dp, modifier: Modifier = Modifier) {
    if (slides.isEmpty()) return
    var index by remember(slides.map { it.key }) { mutableIntStateOf(0) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(index, hovered, slides.size) {
        if (hovered || slides.size < 2) return@LaunchedEffect
        delay(8_000)
        index = (index + 1) % slides.size
    }
    val inset = LocalNavInset.current
    Box(modifier.fillMaxWidth().height(height).hoverable(hover).testTag("hero")) {
        Crossfade(slides[index.coerceIn(0, slides.lastIndex)], animationSpec = tween(600)) { s ->
            Box(Modifier.fillMaxSize()) {
                val art by androidx.compose.runtime.produceState<ImageBitmap?>(null, s.key) { value = s.backdrop() }
                Backdrop(art, Modifier.fillMaxSize(), seed = s.key.hashCode())
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = inset + 16.dp, bottom = 56.dp, end = 40.dp).widthIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.kind.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            color = if (LocalMangaStyle.current) MaterialTheme.colorScheme.tertiary else Color.White.copy(alpha = 0.75f))
                        if (s.telegram) Chip("Telegram", TelegramBlue, Color.White)
                    }
                    Heading(s.title, 54.sp, Modifier.testTag("hero-title"), color = Color.White, maxLines = 2)
                    MetaLine(s.year, s.runtimeMinutes, s.rating, s.genres, onImage = true)
                    s.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.onPlay != null) PillButton(s.playLabel ?: "Play", s.onPlay, icon = Icons.Default.PlayArrow)
                        PillButton("View details", s.onDetails, primary = s.onPlay == null)
                    }
                }
            }
        }
        if (slides.size > 1) {
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hovered) RoundButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", { index = (index - 1 + slides.size) % slides.size }, size = 40.dp)
                slides.indices.forEach { i ->
                    val w by animateDpAsState(if (i == index) 28.dp else 8.dp)
                    Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(Color.White.copy(alpha = if (i == index) 1f else 0.45f)).clickable { index = i })
                }
                if (hovered) RoundButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", { index = (index + 1) % slides.size }, size = 40.dp)
            }
        }
    }
}

/**
 * Artwork behind a hero or a title page, fading into the page on the left and at the bottom so
 * white text reads on any picture. Without a backdrop: speed lines (manga) or a soft gradient.
 * A portrait poster used as a backdrop is blurred.
 */
@Composable
fun Backdrop(image: ImageBitmap?, modifier: Modifier, seed: Int = 0) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(Color.Black)) {
        if (image != null) {
            val portrait = image.height > image.width
            Image(image, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().then(if (portrait) Modifier.blur(28.dp) else Modifier))
        } else if (LocalMangaStyle.current) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).speedLines(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), seed = seed))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1C2230), Color(0xFF0B0B0F)))))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.45f to Color.Black.copy(alpha = 0.35f), 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.25f to Color.Transparent, 0.62f to Color.Transparent, 1f to bg)))
    }
}

/** "2021 · 2h 11m · ★ 7.4 · Drama • Thriller". [onImage]: white text over artwork. */
@Composable
fun MetaLine(year: Int?, runtimeMinutes: Int?, rating: Double?, genres: List<String>, onImage: Boolean = false, extra: @Composable RowScope.() -> Unit = {}) {
    val color = if (onImage) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        year?.let { Text(it.toString(), color = color, fontWeight = FontWeight.SemiBold) }
        runtimeMinutes?.takeIf { it > 0 }?.let { Text(runtimeLabel(it), color = color, fontWeight = FontWeight.SemiBold) }
        rating?.takeIf { it > 0 }?.let { Chip("★ %.1f".format(java.util.Locale.ROOT, it), ImdbYellow, Color.Black) }
        extra()
        if (genres.isNotEmpty()) Text(genres.take(3).joinToString(" • "), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 131 → "2h 11m"; 45 → "45m". */
fun runtimeLabel(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

/**
 * The top of a title page: the artwork full width, a back button, then the title in large type,
 * its details, the description and the actions over the fading artwork.
 */
@Composable
fun TitleHeader(
    title: String, backdrop: ImageBitmap?, onBack: () -> Unit,
    year: Int? = null, runtimeMinutes: Int? = null, rating: Double? = null, genres: List<String> = emptyList(),
    description: String? = null, credits: String? = null, height: Dp = 600.dp, seed: Int = title.hashCode(),
    badges: @Composable RowScope.() -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
) {
    val inset = LocalNavInset.current
    Box(Modifier.fillMaxWidth().height(height)) {
        Backdrop(backdrop, Modifier.fillMaxSize(), seed)
        RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, Modifier.padding(start = inset + 16.dp, top = 20.dp))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = inset + 16.dp, end = 40.dp, bottom = 24.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = badges)
            Heading(title, 52.sp, color = Color.White, maxLines = 2)
            MetaLine(year, runtimeMinutes, rating, genres, onImage = true)
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            credits?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** A section title inside a page, at the page's left edge. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Heading(text, 22.sp, modifier.padding(start = LocalNavInset.current + 16.dp, top = 12.dp, bottom = 10.dp)
        .then(if (LocalMangaStyle.current) Modifier.inkUnderline(MaterialTheme.colorScheme.onSurface) else Modifier))
}

/** A list row for a stream, a file or an episode: a rounded card that brightens on hover. */
@Composable
fun SourceCard(
    title: String, detail: String, source: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, leading: @Composable () -> Unit = { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(26.dp)) },
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = if (LocalMangaStyle.current) MaterialTheme.shapes.medium else RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().padding(start = LocalNavInset.current + 16.dp, end = 40.dp).inkPanel(shape, shadow = 3.dp).clip(shape)
            .background(if (hovered && enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
            .hoverable(hover).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(44.dp).clip(if (LocalMangaStyle.current) MaterialTheme.shapes.small else CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.10f else 0.04f)), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
        if (source.isNotBlank()) Text(source, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A page heading at the top of Library, Search and Settings. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Heading(text, 34.sp, modifier)
}

/** Fills the rest of a row (keeps call sites short). */
@Composable
fun RowScope.Spring() = Box(Modifier.weight(1f).fillMaxHeight())
