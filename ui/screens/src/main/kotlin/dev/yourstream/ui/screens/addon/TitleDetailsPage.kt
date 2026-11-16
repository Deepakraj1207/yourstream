package dev.yourstream.ui.screens.addon

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.metadata.api.MetadataType
import dev.yourstream.metadata.api.TitleDetails
import dev.yourstream.ui.core.Artwork
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.LocalBottomClearance
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.MediaCardModel
import dev.yourstream.ui.core.MediaRow
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.Tile
import dev.yourstream.ui.screens.navigation.CatalogTitleRoute
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.tileCard

/**
 * The title page laid out like Nuvio's: full-width artwork with the title's logo and genres,
 * Play (opens the streams) and a menu, year · length · age rating, ratings, director and writer,
 * the overview, the series' episodes, production companies, cast, trailers, a details table and
 * "More like this". TMDB fills most of it (its key in Settings → TMDB); without it, what the
 * add-on gives.
 */
@Composable
internal fun TitleDetailsPage(
    state: CatalogTitleUiState,
    onAction: (CatalogTitleAction) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: (Long) -> Unit,
    onOpen: (OpenTarget) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val layout = dev.yourstream.ui.screens.settings.LocalAppSettings.current.layout
    val d = state.details
    val m = state.meta
    val list = rememberLazyListState()
    val heroHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.42f).coerceIn(300.dp, 560.dp)
    val heroPx = with(LocalDensity.current) { heroHeight.toPx() }
    // The top bar fills in (with the logo) once the artwork has scrolled away.
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > heroPx * 0.7f } }
    val title = d?.title?.ifBlank { null } ?: state.title
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (layout.detailBackground == dev.yourstream.ui.screens.settings.DetailBackground.CINEMATIC) CinematicBackground(d?.backdropUrl ?: state.poster ?: m?.backdropUrl)
        LazyColumn(Modifier.fillMaxSize().testTag("catalog-title"), state = list, contentPadding = PaddingValues(bottom = LocalBottomClearance.current + 24.dp)) {
            item(key = "hero") { Hero(state, title, heroHeight) }
            item(key = "actions") { Actions(state, onAction, onOpenLibrary) }
            // Reported from a device: the page drew the add-on's details, then TMDB's replaced
            // them and everything jumped. While TMDB answers (at most a few seconds), its space.
            if (state.detailsLoading) {
                item(key = "details-loading") { DetailsPlaceholder() }
                return@LazyColumn
            }
            item(key = "facts") { Facts(state) }
            if (layout.showOverview) item(key = "overview") { Overview(d?.overview ?: m?.description) }
            if (state.series) episodes(state, onAction, layout.episodeCards == dev.yourstream.ui.screens.settings.EpisodeCards.HORIZONTAL)
            d?.companies?.takeIf { it.isNotEmpty() }?.let { c -> item(key = "production") { Production(c) } }
            if (layout.showCast) cast(d, m?.cast.orEmpty())
            d?.videos?.takeIf { it.isNotEmpty() }?.let { v -> item(key = "trailers") { Trailers(v, onOpenUrl) } }
            item(key = "details-table") { DetailsTable(state) }
            d?.recommendations?.takeIf { it.isNotEmpty() }?.let { r -> item(key = "similar") { MoreLikeThis(r, onOpen) } }
            if (d != null) item(key = "tmdb") {
                Text(stringResource(R.string.title_powered_by_tmdb), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(horizontal = LocalDimens.current.gutter, vertical = 12.dp))
            }
        }
        TopBar(state, title, collapsed, onBack, onAction)
    }
}

/** Back on the left, the logo (once scrolled) in the middle, "My library" on the right. */
@Composable
private fun TopBar(state: CatalogTitleUiState, title: String, collapsed: Boolean, onBack: () -> Unit, onAction: (CatalogTitleAction) -> Unit) {
    val bg by animateColorAsState(if (collapsed) MaterialTheme.colorScheme.background else Color.Transparent, label = "title-bar")
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 6.dp).heightIn(min = 56.dp).testTag("title-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.focusHighlight(CircleShape).testTag("title-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.title_back), tint = if (collapsed) MaterialTheme.colorScheme.onBackground else Color.White)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (collapsed) {
                val logo = state.details?.logoUrl
                if (logo != null) Artwork(logo, title, Modifier.height(40.dp).widthIn(max = 200.dp).fillMaxWidth(), contentScale = ContentScale.Fit, showInitials = false, placeholder = false)
                else Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val inLibrary = state.inLibrary
        if (inLibrary != null) {
            val haptics = dev.yourstream.ui.core.rememberHaptics()
            IconButton(onClick = { haptics.toggle(!inLibrary); onAction(CatalogTitleAction.ToggleLibrary) }, modifier = Modifier.focusHighlight(CircleShape).testTag("title-library")) {
                Icon(if (inLibrary) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = stringResource(if (inLibrary) R.string.title_remove_library else R.string.title_add_library),
                    tint = if (collapsed) MaterialTheme.colorScheme.onBackground else Color.White)
            }
        } else Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun Hero(state: CatalogTitleUiState, title: String, height: androidx.compose.ui.unit.Dp) {
    val d = state.details
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(height).testTag("title-hero")) {
        // The poster from the list (already loaded) at once; TMDB's backdrop is drawn over it when
        // it has loaded, so the picture never blanks while the larger image comes in.
        Artwork(state.meta?.backdropUrl ?: state.poster ?: d?.posterUrl, title, Modifier.fillMaxSize(), showInitials = false)
        d?.backdropUrl?.let { b -> Artwork(b, title, Modifier.fillMaxSize().testTag("title-backdrop"), showInitials = false, placeholder = false) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.35f), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 0.85f to background.copy(alpha = 0.9f), 1f to background,
        )))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val logo = d?.logoUrl
            if (state.detailsLoading) {
                // The logo's place, kept empty until TMDB answers (no title text swapped for a logo).
                Box(Modifier.fillMaxWidth(0.75f).height(110.dp).testTag("title-logo-space"))
            } else if (logo != null) {
                Artwork(logo, title, Modifier.fillMaxWidth(0.75f).height(110.dp).testTag("title-logo"), contentScale = ContentScale.Fit, showInitials = false, placeholder = false)
            } else {
                Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            }
            val genres = d?.genres?.ifEmpty { null } ?: state.meta?.genres.orEmpty()
            if (state.detailsLoading) Box(Modifier.height(40.dp))
            else if (genres.isNotEmpty()) {
                Text(genres.take(3).joinToString(" • "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).testTag("title-genres"))
            }
        }
    }
}

/** Play (the streams page) and the "…" menu. */
@Composable
private fun Actions(state: CatalogTitleUiState, onAction: (CatalogTitleAction) -> Unit, onOpenLibrary: (Long) -> Unit) {
    val g = LocalDimens.current.gutter
    var menu by remember { mutableStateOf(false) }
    val haptics = dev.yourstream.ui.core.rememberHaptics()
    Row(Modifier.fillMaxWidth().padding(horizontal = g, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val episode = state.episode?.takeIf { state.series }
        Button(
            onClick = { haptics.perform(dev.yourstream.ui.core.Haptic.CONFIRM); onAction(CatalogTitleAction.Play) }, shape = CircleShape, enabled = !state.series || state.episodes.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            modifier = Modifier.weight(1f).height(56.dp).focusHighlight(CircleShape).testTag("title-play"),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(28.dp))
            Text(if (episode?.season != null && episode.episode != null) stringResource(R.string.title_play_episode, episode.season!!, episode.episode!!) else stringResource(R.string.title_play),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        Box {
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(56.dp).clip(CircleShape).clickable { menu = true }.focusHighlight(CircleShape).testTag("title-more"),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape)) }
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                state.libraryItemId?.let { id ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.addon_in_library)) }, onClick = { menu = false; onOpenLibrary(id) }, modifier = Modifier.testTag("open-library"))
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.addon_streams_refresh)) }, onClick = { menu = false; onAction(CatalogTitleAction.RefreshStreams) })
            }
        }
    }
}

/** "2026   2h 39m   [U/A 13+]", the ratings, then Director and Writer. */
@Composable
private fun Facts(state: CatalogTitleUiState) {
    val d = state.details
    val m = state.meta
    val g = LocalDimens.current.gutter
    Column(Modifier.fillMaxWidth().padding(horizontal = g), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val year = d?.year ?: state.year ?: m?.year
        val runtime = d?.runtimeMinutes ?: m?.runtimeMinutes
        val certification = d?.certification
        if (year != null || runtime != null || certification != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("title-facts")) {
                year?.let { Text(it.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                runtime?.let { Text(dev.yourstream.ui.screens.home.runtimeLabel(it), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                certification?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("title-certification"))
                }
            }
        }
        val addonRating = m?.rating?.takeIf { it > 0 }
        val tmdbRating = d?.tmdbRating
        if (addonRating != null || tmdbRating != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.testTag("title-ratings")) {
                addonRating?.let { r ->
                    if (state.ratingIsImdb) Rating("IMDb", Color(0xFFF5C518), Color.Black, "%.1f".format(java.util.Locale.ROOT, r), Color(0xFFF5C518))
                    else Text("★ %.1f".format(java.util.Locale.ROOT, r), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF5C518))
                }
                // TMDB scores out of 10 ("6.7"), like IMDb.
                tmdbRating?.let { r -> Rating("TMDB", Color(0xFF0D253F), Color(0xFF01B4E4), "%.1f".format(java.util.Locale.ROOT, r), Color(0xFF01B4E4)) }
            }
        }
        val crew = listOf(
            R.string.title_director to (d?.directors?.ifEmpty { null } ?: m?.directors.orEmpty()),
            R.string.title_creator to d?.creators.orEmpty(),
            R.string.title_writer to d?.writers.orEmpty(),
        ).filter { it.second.isNotEmpty() }
        crew.forEach { (label, names) ->
            val l = stringResource(label)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("$l  ") }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(names.take(3).joinToString(", ")) }
            }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Rating(badge: String, badgeColor: Color, badgeText: Color, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("rating:$badge")) {
        Text(badge, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = badgeText,
            modifier = Modifier.background(badgeColor, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

/** Three lines, then "Show More". */
@Composable
private fun Overview(text: String?) {
    if (text.isNullOrBlank()) return
    var open by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = LocalDimens.current.gutter, vertical = 14.dp).testTag("title-overview")) {
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) overflows = it.hasVisualOverflow })
        if (overflows || open) {
            Text(stringResource(if (open) R.string.title_show_less else R.string.title_show_more), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { open = !open }.focusHighlight().padding(vertical = 4.dp).testTag("title-show-more"))
        }
    }
}

private fun LazyListScope.episodes(state: CatalogTitleUiState, onAction: (CatalogTitleAction) -> Unit, cards: Boolean) {
    when {
        state.loadingMeta -> item(key = "episodes-loading") { TitleLoading() }
        state.seasons.isEmpty() -> item(key = "episodes-none") { TitleNote(stringResource(R.string.addon_no_episodes)) }
        else -> {
            item(key = "seasons") {
                ChipRow(state.seasons.map { ChipOption(it, if (it == 0) stringResource(R.string.addon_specials) else stringResource(R.string.addon_season, it)) },
                    state.season, { onAction(CatalogTitleAction.SelectSeason(it)) }, Modifier.padding(vertical = 8.dp))
            }
            // Tapping an episode opens its streams.
            if (cards) item(key = "episode-row") { EpisodeCardsRow(state.episodes, state.episode?.id) { onAction(CatalogTitleAction.SelectEpisode(it)) } }
            else items(state.episodes, key = { "ep:" + it.id }) { e -> EpisodeRow(e, selected = e.id == state.episode?.id) { onAction(CatalogTitleAction.SelectEpisode(e)) } }
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = LocalDimens.current.gutter).padding(top = 24.dp, bottom = 12.dp).semantics { heading() })
}

/** Production companies: their logos on light tiles (the name when there is no logo). */
@Composable
private fun Production(companies: List<TitleDetails.Company>) {
    val g = LocalDimens.current.gutter
    Column(Modifier.testTag("title-production")) {
        SectionHeading(stringResource(R.string.title_production))
        LazyRow(contentPadding = PaddingValues(horizontal = g), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(companies, key = { it.name }) { c ->
                Box(Modifier.width(100.dp).height(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE0E0E0)).padding(10.dp), contentAlignment = Alignment.Center) {
                    if (c.logoUrl != null) Artwork(c.logoUrl, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, showInitials = false, placeholder = false)
                    else Text(c.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Round photos with the name and the role (TMDB), else the add-on's names. */
private fun LazyListScope.cast(d: TitleDetails?, names: List<String>) {
    val people = d?.cast?.ifEmpty { null } ?: names.map { TitleDetails.Person(it, null, null) }
    if (people.isEmpty()) return
    item(key = "cast") {
        val g = LocalDimens.current.gutter
        Column(Modifier.testTag("title-cast")) {
            SectionHeading(stringResource(R.string.title_cast))
            LazyRow(contentPadding = PaddingValues(horizontal = g), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(people.take(20), key = { it.name + it.role }) { p ->
                    Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(p.photoUrl, p.name, Modifier.size(96.dp).clip(CircleShape))
                        Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                        p.role?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** Trailers on YouTube, with a drop-down for the kind (Trailer, Teaser, Clip, …). */
@Composable
private fun Trailers(videos: List<TitleDetails.Video>, onOpenUrl: (String) -> Unit) {
    val g = LocalDimens.current.gutter
    val kinds = videos.map { it.type }.distinct()
    var kind by rememberSaveable { mutableStateOf(kinds.first()) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.testTag("title-trailers")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(stringResource(R.string.title_trailers))
            if (kinds.size > 1 || kinds.first() != kind) Box(Modifier.padding(top = 12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clip(CircleShape).clickable { menu = true }.focusHighlight(CircleShape).testTag("trailer-kind")) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(kind, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    kinds.forEach { k -> DropdownMenuItem(text = { Text(k) }, onClick = { kind = k; menu = false }) }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = g), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(videos.filter { it.type == kind }, key = { it.youtubeKey }) { v ->
                Column(Modifier.width(240.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpenUrl(v.watchUrl) }.focusHighlight().testTag("trailer:${v.youtubeKey}")) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) {
                        Artwork(v.thumbnailUrl, v.name, Modifier.fillMaxSize(), showInitials = false)
                        Box(Modifier.align(Alignment.Center).size(44.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                        }
                    }
                    Text(v.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                    v.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** "Movie Details" / "Show Details": status, release, length, age rating, country, language. */
@Composable
private fun DetailsTable(state: CatalogTitleUiState) {
    val d = state.details
    val m = state.meta
    val rows = listOfNotNull(
        d?.status?.let { R.string.title_status to it },
        (d?.releaseDate?.take(4) ?: (d?.year ?: state.year ?: m?.year)?.toString())?.let { R.string.title_release to it },
        (d?.runtimeMinutes ?: m?.runtimeMinutes)?.let { R.string.title_runtime to dev.yourstream.ui.screens.home.runtimeLabel(it) },
        d?.seasonCount?.takeIf { state.series }?.let { R.string.title_seasons to it.toString() },
        d?.episodeCount?.takeIf { state.series }?.let { R.string.title_episodes to it.toString() },
        d?.certification?.let { R.string.title_certification to it },
        d?.originCountries?.takeIf { it.isNotEmpty() }?.let { R.string.title_country to it.joinToString(", ") },
        (d?.originalLanguage ?: m?.originalLanguage)?.let { R.string.title_language to it.uppercase() },
    )
    if (rows.isEmpty()) return
    val g = LocalDimens.current.gutter
    Column(Modifier.testTag("title-details")) {
        SectionHeading(stringResource(if (state.series) R.string.title_show_details else R.string.title_movie_details))
        rows.forEachIndexed { i, (label, value) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = g, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
            if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = g), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

/** TMDB's recommendations; they open their own title page (TMDB gives their IMDb id for streams). */
@Composable
private fun MoreLikeThis(items: List<TitleDetails.Recommendation>, onOpen: (OpenTarget) -> Unit) {
    val tiles = items.map { r ->
        val type = if (r.type == MetadataType.SERIES) CatalogType.SERIES else CatalogType.MOVIE
        Tile(MediaCardModel("rec:${r.tmdbId}", r.title, r.year?.toString(), posterUrl = r.posterUrl),
            OpenTarget.CatalogTitle(CatalogTitleRoute("tmdb", type.name, "tmdb:${r.tmdbId}", r.title, r.year, r.posterUrl, null)))
    }
    Column(Modifier.padding(top = 16.dp).testTag("title-similar")) {
        MediaRow(stringResource(R.string.title_more_like_this), tiles, tileCard(), { onOpen(it.open) })
    }
}

/** The details' space while TMDB answers: soft bars where the year, ratings, crew and overview go. */
@Composable
private fun DetailsPlaceholder() {
    val g = LocalDimens.current.gutter
    val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(Modifier.fillMaxWidth().padding(horizontal = g, vertical = 8.dp).testTag("title-details-loading"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(0.45f, 0.35f, 0.6f, 0.55f, 1f, 1f, 0.7f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(18.dp).clip(RoundedCornerShape(6.dp)).background(bar))
        }
    }
}

@Composable
internal fun TitleLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
internal fun TitleNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LocalDimens.current.gutter, vertical = 12.dp))
}

