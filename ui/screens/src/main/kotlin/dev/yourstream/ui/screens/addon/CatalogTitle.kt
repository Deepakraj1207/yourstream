package dev.yourstream.ui.screens.addon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.catalog.api.AddonStream
import dev.yourstream.catalog.api.CatalogItem
import dev.yourstream.catalog.api.CatalogItemRef
import dev.yourstream.catalog.api.CatalogMetadata
import dev.yourstream.catalog.api.CatalogProviderId
import dev.yourstream.catalog.api.CatalogType
import dev.yourstream.catalog.api.CatalogVideo
import dev.yourstream.catalog.sync.CatalogAddons
import dev.yourstream.core.common.AppResult
import dev.yourstream.ui.core.Artwork
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.CatalogTitleRoute
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.ui.screens.navigation.StreamPlayerRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** Streams of one add-on for the selected movie/episode. */
data class StreamGroup(val addon: String, val streams: List<AddonStream>, val error: String? = null)

data class CatalogTitleUiState(
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val series: Boolean = false,
    val meta: CatalogMetadata? = null,
    val loadingMeta: Boolean = true,
    /** Library item with this title, if any (its Telegram files play from there). */
    val libraryItemId: Long? = null,
    val seasons: List<Int> = emptyList(),
    val season: Int? = null,
    /** The episode whose streams are shown (series). */
    val episode: CatalogVideo? = null,
    val streams: List<StreamGroup> = emptyList(),
    val loadingStreams: Boolean = false,
    /** No enabled add-on offers streams. */
    val noStreamAddons: Boolean = false,
) {
    val episodes: List<CatalogVideo> get() = meta?.videos.orEmpty().filter { it.season == season }
}

sealed interface CatalogTitleAction {
    data class SelectSeason(val season: Int) : CatalogTitleAction
    data class SelectEpisode(val video: CatalogVideo) : CatalogTitleAction
    data object Retry : CatalogTitleAction
}

/**
 * Like a Stremio/Nuvio title page: details from the add-ons (or Cinemeta), seasons and episodes
 * for series, and the streams every enabled stream add-on offers for the movie or chosen episode.
 */
@HiltViewModel
class CatalogTitleViewModel @Inject constructor(saved: SavedStateHandle, private val addons: CatalogAddons) : ViewModel() {
    private val route = saved.toRoute<CatalogTitleRoute>()
    private val type = runCatching { CatalogType.valueOf(route.type) }.getOrDefault(CatalogType.MOVIE)
    private val item = CatalogItem(
        CatalogItemRef(CatalogProviderId(route.provider), type, route.id, route.imdb ?: route.id.takeIf { it.startsWith("tt") }),
        route.title, route.year, route.poster,
    )
    private val _state = MutableStateFlow(CatalogTitleUiState(route.title, route.year, route.poster, series = type == CatalogType.SERIES))
    val state: StateFlow<CatalogTitleUiState> = _state.asStateFlow()

    /** What stream add-ons are asked for: the IMDb id (what they key on), else the add-on's own id. */
    private val streamId = item.ref.imdbId ?: item.ref.id

    init { load() }

    fun onAction(a: CatalogTitleAction) {
        when (a) {
            is CatalogTitleAction.SelectSeason -> _state.update { it.copy(season = a.season) }
            is CatalogTitleAction.SelectEpisode -> { _state.update { it.copy(episode = a.video) }; loadStreams(CatalogType.SERIES, a.video.id) }
            CatalogTitleAction.Retry -> load()
        }
    }

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(loadingMeta = true) }
        val presence = runCatching { addons.manager().withLibraryPresence(listOf(item)).firstOrNull()?.second }.getOrNull()
        val meta = runCatching { addons.metadata(item) }.getOrNull()
        val seasons = meta?.videos.orEmpty().mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
        _state.update {
            it.copy(
                meta = meta, loadingMeta = false, libraryItemId = presence?.itemId,
                poster = it.poster ?: meta?.posterUrl, year = it.year ?: meta?.year,
                seasons = seasons, season = it.season ?: seasons.firstOrNull(),
            )
        }
        if (type == CatalogType.MOVIE) loadStreams(CatalogType.MOVIE, streamId)
    }

    private fun loadStreams(t: CatalogType, id: String) = viewModelScope.launch {
        _state.update { it.copy(loadingStreams = true, streams = emptyList(), noStreamAddons = false) }
        val providers = addons.streamProviders()
        if (providers.isEmpty()) { _state.update { it.copy(loadingStreams = false, noStreamAddons = true) }; return@launch }
        val groups = coroutineScope {
            providers.map { p ->
                async {
                    when (val r = runCatching { p.streams(t, id) }.getOrElse { AppResult.Err(dev.yourstream.core.common.AppError.Network()) }) {
                        is AppResult.Ok -> StreamGroup(p.displayName, r.value)
                        is AppResult.Err -> StreamGroup(p.displayName, emptyList(), r.error.message)
                    }
                }
            }.awaitAll()
        }
        // Playable streams first; add-ons with nothing at the end.
        _state.update { it.copy(loadingStreams = false, streams = groups.sortedBy { g -> if (g.streams.any { s -> s.playable }) 0 else 1 }) }
    }

    fun playTarget(s: AddonStream): OpenTarget.Stream? {
        val url = s.url?.takeIf { s.playable } ?: return null
        val st = _state.value
        val subtitle = st.episode?.let { e -> listOfNotNull(e.season?.let { "S%02dE%02d".format(it, e.episode ?: 0) }, e.title).joinToString(" · ") }
        val headers = JsonObject(s.headers.mapValues { JsonPrimitive(it.value) }).toString()
        return OpenTarget.Stream(StreamPlayerRoute(url, st.title, subtitle, headers))
    }
}

@Composable
fun CatalogTitleDestination(onOpen: (OpenTarget) -> Unit, onBack: () -> Unit, vm: CatalogTitleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CatalogTitleScreen(state, vm::onAction, onBack, onOpenLibrary = { onOpen(OpenTarget.Details(it)) }) { s ->
        vm.playTarget(s)?.let(onOpen) ?: s.externalUrl?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

@Composable
fun CatalogTitleScreen(
    state: CatalogTitleUiState,
    onAction: (CatalogTitleAction) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: (Long) -> Unit,
    onStream: (AddonStream) -> Unit,
) {
    val d = LocalDimens.current
    ScreenScaffold("", onBack = onBack) {
        LazyColumn(Modifier.fillMaxSize().testTag("catalog-title"), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Header(state, onOpenLibrary) }
            if (state.series) {
                if (state.loadingMeta) item { Loading() }
                else if (state.seasons.isEmpty()) item { Note(stringResource(R.string.addon_no_episodes)) }
                else {
                    item {
                        ChipRow(state.seasons.map { ChipOption(it, if (it == 0) stringResource(R.string.addon_specials) else stringResource(R.string.addon_season, it)) },
                            state.season, { onAction(CatalogTitleAction.SelectSeason(it)) }, Modifier.padding(vertical = 8.dp))
                    }
                    items(state.episodes, key = { "ep:" + it.id }) { e ->
                        EpisodeRow(e, selected = e.id == state.episode?.id) { onAction(CatalogTitleAction.SelectEpisode(e)) }
                        if (e.id == state.episode?.id) Streams(state, onStream, Modifier.padding(start = d.gutter + 16.dp, end = d.gutter))
                    }
                }
            } else {
                item { SectionTitle(stringResource(R.string.addon_streams)) }
                item { Streams(state, onStream, Modifier.padding(horizontal = d.gutter)) }
            }
        }
    }
}

@Composable
private fun Header(state: CatalogTitleUiState, onOpenLibrary: (Long) -> Unit) {
    val d = LocalDimens.current
    Row(Modifier.padding(horizontal = d.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Artwork(state.poster, state.title, Modifier.width(d.posterWidth).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            val m = state.meta
            listOfNotNull(state.year?.toString(), m?.runtimeMinutes?.let { "$it min" }, m?.rating?.let { "★ %.1f".format(it) }, m?.genres?.take(3)?.joinToString(", ")?.ifEmpty { null })
                .joinToString(" · ").ifEmpty { null }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.libraryItemId?.let { id ->
                Button(onClick = { onOpenLibrary(id) }, modifier = Modifier.focusHighlight(MaterialTheme.shapes.extraLarge).testTag("open-library")) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text(stringResource(R.string.addon_in_library), Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    state.meta?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = d.gutter, vertical = 8.dp)) }
    state.meta?.cast?.takeIf { it.isNotEmpty() }?.let {
        Text(stringResource(R.string.addon_cast, it.take(6).joinToString(", ")), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = d.gutter))
    }
}

@Composable
private fun EpisodeRow(e: CatalogVideo, selected: Boolean, onClick: () -> Unit) {
    val d = LocalDimens.current
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().focusHighlight().clickable(onClick = onClick).testTag("episode:${e.id}"),
    ) {
        Row(Modifier.padding(horizontal = d.gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small)) {
                Artwork(e.thumbnail, e.title ?: "E${e.episode}", Modifier.fillMaxSize(), showInitials = false)
            }
            Column(Modifier.weight(1f)) {
                Text(listOfNotNull(e.episode?.let { "$it." }, e.title).joinToString(" "), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                e.released?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Streams(state: CatalogTitleUiState, onStream: (AddonStream) -> Unit, modifier: Modifier) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.loadingStreams -> Loading()
            state.noStreamAddons -> Note(stringResource(R.string.addon_no_stream_addons))
            state.streams.all { it.streams.isEmpty() } -> Note(stringResource(R.string.addon_no_streams))
            else -> state.streams.filter { it.streams.isNotEmpty() }.forEach { g ->
                Text(g.addon, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                g.streams.forEach { s -> StreamRow(s, onStream) }
            }
        }
    }
}

@Composable
private fun StreamRow(s: AddonStream, onStream: (AddonStream) -> Unit) {
    val usable = s.playable || s.externalUrl != null
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().focusHighlight().clickable(enabled = usable) { onStream(s) }.testTag("stream"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.name.replace('\n', ' '), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Column(Modifier.weight(1f)) {
                s.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                when {
                    s.playable -> Unit
                    s.externalUrl != null -> Text(stringResource(R.string.addon_stream_external), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text(stringResource(R.string.addon_stream_torrent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (s.playable) Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_play))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = LocalDimens.current.gutter, vertical = 12.dp).semantics { heading() })
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LocalDimens.current.gutter, vertical = 12.dp).testTag("addon-note"))
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
}
