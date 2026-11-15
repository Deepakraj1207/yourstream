package dev.yourstream.ui.screens.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.ui.core.Artwork
import dev.yourstream.ui.core.LocalReducedMotion
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.OpenTarget
import kotlinx.coroutines.delay

/**
 * Home's hero carousel, laid out like Nuvio's: full-width artwork fading into the page, the title
 * in large type, "Movie · Romance · 2h 11m · 2026", a rating and language badge, "View Details",
 * and dots for the slides. Swipe between slides; they also move on every [ADVANCE_MS] while
 * nobody touches them (not with reduced motion). Phones show the poster, wider screens the
 * backdrop when there is one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(slides: List<HeroSlide>, onOpen: (OpenTarget) -> Unit, modifier: Modifier = Modifier) {
    if (slides.isEmpty()) return
    val pager = rememberPagerState { slides.size }
    val reduced = LocalReducedMotion.current
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager.currentPage, dragged, reduced, slides.size) {
        if (reduced || dragged || slides.size < 2) return@LaunchedEffect
        delay(ADVANCE_MS)
        pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
    }
    val compact = LocalWindowLayout.current == WindowLayout.COMPACT
    val screen = LocalConfiguration.current.screenHeightDp.dp
    val height = if (compact) (screen * 0.66f).coerceIn(420.dp, 680.dp) else (screen * 0.6f).coerceIn(360.dp, 620.dp)
    Column(modifier.fillMaxWidth().testTag("hero-carousel")) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(height), key = { slides[it].key }) { page ->
            Slide(slides[page], compact, onOpen)
        }
        Dots(slides.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
    }
}

@Composable
private fun Slide(s: HeroSlide, compact: Boolean, onOpen: (OpenTarget) -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().testTag("hero-slide:${s.key}")) {
        Artwork(if (compact) s.posterUrl ?: s.backdropUrl else s.backdropUrl ?: s.posterUrl, s.title, Modifier.fillMaxSize(), showInitials = false)
        // Fades into the page so the title reads on any artwork.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent, 0.45f to Color.Transparent, 0.8f to background.copy(alpha = 0.85f), 1f to background,
        )))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(s.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            val type = stringResource(if (s.type == CatalogType.SERIES) R.string.hero_series else R.string.hero_movie)
            Text(
                listOfNotNull(type, s.genre, s.runtimeMinutes?.let(::runtimeLabel), s.year?.toString()).joinToString("  •  "),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("hero-meta"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                s.rating?.takeIf { it > 0 }?.let { r ->
                    Text("★ %.1f".format(java.util.Locale.ROOT, r), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFF5C518)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("hero-rating"))
                }
                s.languages.take(2).takeIf { it.isNotEmpty() }?.let { l ->
                    Text(l.joinToString(" · ") { it.uppercase() }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Button(
                onClick = { onOpen(s.open) }, shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.heightIn(min = 52.dp).width(220.dp).focusHighlight(CircleShape).testTag("hero-details"),
            ) { Text(stringResource(R.string.hero_view_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
    }
}

/** One dot per slide; the current one a wider pill. */
@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier) {
    if (count < 2) return
    Row(modifier.testTag("hero-dots"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(Modifier.animateContentSize().height(8.dp).width(if (i == current) 28.dp else 8.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (i == current) 1f else 0.45f)))
        }
    }
}

/** 131 → "2h 11m"; 45 → "45m". */
internal fun runtimeLabel(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

private const val ADVANCE_MS = 6_000L
