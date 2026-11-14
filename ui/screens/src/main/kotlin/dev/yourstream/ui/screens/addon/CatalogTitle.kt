package dev.yourstream.ui.screens.addon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/**
 * Streams of one add-on for the selected movie/episode. [id] tells add-ons with the same name
 * apart; [loading] while the add-on has not answered yet.
 */
data class StreamGroup(
    val addon: String,
    val streams: List<AddonStream>,
    val error: String? = null,
    val id: String = addon,
    val loading: Boolean = false,
) {
    companion object {
        /**
         * The order groups are shown in, kept stable while answers arrive: add-ons with direct
         * (HTTP) streams first, then torrents, then the ones still loading, then those that found
         * nothing or did not answer. Within each, the order the add-ons are listed in.
         */
        fun ordered(groups: List<StreamGroup>): List<StreamGroup> = groups.sortedBy { g ->
            when {
                g.streams.any { it.playable } -> 0
                g.streams.any { it.isTorrent } -> 1
                g.streams.isNotEmpty() -> 2
                g.loading -> 3
                else -> 4
            }
        }
    }
}

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
    /** The add-on whose streams are shown alone ([StreamGroup.id]); null: all add-ons, grouped. */
    val streamAddon: String? = null,
) {
    val episodes: List<CatalogVideo> get() = meta?.videos.orEmpty().filter { it.season == season }

    /** Groups with something to show or still loading: the add-on chips. */
    val streamTabs: List<StreamGroup> get() = streams.filter { it.streams.isNotEmpty() || it.loading }

    /** The groups on screen: one add-on when its chip is chosen (and it is still there), else all with streams. */
    val shownStreams: List<StreamGroup> get() {
        val one = streamAddon?.let { id -> streams.firstOrNull { it.id == id && (it.streams.isNotEmpty() || it.loading) } }
        return if (one != null) listOf(one) else streams.filter { it.streams.isNotEmpty() }
    }
}

sealed interface CatalogTitleAction {
    data class SelectSeason(val season: Int) : CatalogTitleAction
    data class SelectEpisode(val video: CatalogVideo) : CatalogTitleAction
    data object Retry : CatalogTitleAction
    /** Show one add-on's streams ([StreamGroup.id]), or all of them (null). */
    data class SelectStreamAddon(val id: String?) : CatalogTitleAction
    /** Ask the stream add-ons again for the same movie or episode. */
    data object RefreshStreams : CatalogTitleAction
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

    /** The IMDb id once known (from the route, or from the details): what most stream add-ons key on. */
    private var imdb: String? = item.ref.imdbId

    init { load() }

    fun onAction(a: CatalogTitleAction) {
        when (a) {
            is CatalogTitleAction.SelectSeason -> _state.update { it.copy(season = a.season) }
            is CatalogTitleAction.SelectEpisode -> { _state.update { it.copy(episode = a.video) }; loadStreams(CatalogType.SERIES, episodeIds(a.video)) }
            CatalogTitleAction.Retry -> load()
            is CatalogTitleAction.SelectStreamAddon -> _state.update { it.copy(streamAddon = a.id) }
            CatalogTitleAction.RefreshStreams -> lastStreams?.let { (t, ids) -> loadStreams(t, ids) }
        }
    }

    /** An episode as add-ons know it: its own id ("kitsu:1:5", "tt…:1:2"), and the IMDb form when that differs. */
    private fun episodeIds(v: CatalogVideo): List<String> = listOfNotNull(
        v.id,
        imdb?.let { i -> if (v.season != null && v.episode != null) "$i:${v.season}:${v.episode}" else null },
    ).distinct()

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(loadingMeta = true) }
        val presence = runCatching { addons.manager().withLibraryPresence(listOf(item)).firstOrNull()?.second }.getOrNull()
        val meta = runCatching { addons.metadata(item) }.getOrNull()
        imdb = imdb ?: meta?.ref?.imdbId
        val seasons = meta?.videos.orEmpty().mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
        _state.update {
            it.copy(
                meta = meta, loadingMeta = false, libraryItemId = presence?.itemId,
                poster = it.poster ?: meta?.posterUrl, year = it.year ?: meta?.year,
                seasons = seasons, season = it.season ?: seasons.firstOrNull(),
            )
        }
        if (type == CatalogType.MOVIE) loadStreams(CatalogType.MOVIE, listOfNotNull(imdb, item.ref.id).distinct())
    }

    /** What the streams on screen are for, so the refresh can ask again. */
    private var lastStreams: Pair<CatalogType, List<String>>? = null
    private var streamJob: Job? = null

    /**
     * Asks every enabled stream add-on at once, each with the first id form it declares it
     * handles (an anime add-on gets "kitsu:…", most others the IMDb id); add-ons that handle
     * none of them are dropped. Each add-on's streams appear as soon as it answers, like Nuvio,
     * instead of after the slowest one. A newer request (another episode, a refresh) cancels the
     * older one, so a slow answer for the previous episode cannot replace the current list.
     */
    private fun loadStreams(t: CatalogType, ids: List<String>) {
        lastStreams = t to ids
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _state.update { it.copy(loadingStreams = true, streams = emptyList(), noStreamAddons = false) }
            val providers = addons.streamProviders()
            if (providers.isEmpty()) { _state.update { it.copy(loadingStreams = false, noStreamAddons = true) }; return@launch }
            val keys = providers.map { it.id.value }
            _state.update { s -> s.copy(streams = providers.map { p -> StreamGroup(p.displayName, emptyList(), id = p.id.value, loading = true) }) }
            fun answer(key: String, g: StreamGroup?) = _state.update { s ->
                val next = if (g == null) s.streams.filterNot { it.id == key } else s.streams.map { if (it.id == key) g else it }
                // Listed order first (stable), then the display order.
                s.copy(streams = StreamGroup.ordered(next.sortedBy { keys.indexOf(it.id) }))
            }
            coroutineScope {
                providers.forEach { p ->
                    launch {
                        val key = p.id.value
                        val id = ids.firstOrNull { runCatching { p.handles(t, it) }.getOrDefault(false) }
                        if (id == null) { answer(key, null); return@launch }
                        val r = runCatching { p.streams(t, id) }.getOrElse { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            AppResult.Err(dev.yourstream.core.common.AppError.Network())
                        }
                        answer(key, when (r) {
                            is AppResult.Ok -> StreamGroup(p.displayName, r.value, id = key)
                            is AppResult.Err -> StreamGroup(p.displayName, emptyList(), r.error.message, id = key)
                        })
                    }
                }
            }
            _state.update { it.copy(loadingStreams = false) }
        }
    }

    fun playTarget(s: AddonStream): OpenTarget.Stream? {
        val st = _state.value
        val subtitle = st.episode?.let { e -> listOfNotNull(e.season?.let { "S%02dE%02d".format(it, e.episode ?: 0) }, e.title).joinToString(" · ") }
        // Any stream of this movie or episode resumes where the last one stopped.
        val progress = dev.yourstream.watch.engine.StreamProgressStore.keyOf(route.type, route.id, item.ref.imdbId, st.episode?.season, st.episode?.episode)
        if (s.isTorrent) {
            return OpenTarget.Stream(StreamPlayerRoute("", st.title, subtitle, infoHash = s.infoHash, fileIdx = s.fileIdx ?: -1,
                sources = s.sources.joinToString("\n"), fileName = s.fileName, progressKey = progress))
        }
        val url = s.url?.takeIf { s.playable } ?: return null
        val headers = JsonObject(s.headers.mapValues { JsonPrimitive(it.value) }).toString()
        return OpenTarget.Stream(StreamPlayerRoute(url, st.title, subtitle, headers, progressKey = progress))
    }
}

@Composable
fun CatalogTitleDestination(onOpen: (OpenTarget) -> Unit, onBack: () -> Unit, vm: CatalogTitleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val torrents = dev.yourstream.ui.screens.settings.LocalAppSettings.current.torrentStreaming
    val prefs: dev.yourstream.ui.screens.settings.AppPreferencesViewModel = hiltViewModel()
    // A torrent result tapped while torrents are off: ask (IP address visible), then play it.
    var askFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<AddonStream?>(null) }
    CatalogTitleScreen(state, vm::onAction, onBack, onOpenLibrary = { onOpen(OpenTarget.Details(it)) }, torrents = torrents) { s ->
        if (s.isTorrent && !torrents && vm.playTarget(s) != null) askFor = s
        else vm.playTarget(s)?.takeIf { torrents || !s.isTorrent }?.let(onOpen)
            ?: s.externalUrl?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
    askFor?.let { s ->
        dev.yourstream.ui.screens.settings.TorrentConsentDialog(
            onEnable = { prefs.update { it.copy(torrentStreaming = true) }; vm.playTarget(s)?.let(onOpen) },
            onClose = { askFor = null },
        )
    }
}

@Composable
fun CatalogTitleScreen(
    state: CatalogTitleUiState,
    onAction: (CatalogTitleAction) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: (Long) -> Unit,
    torrents: Boolean = true,
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
                        if (e.id == state.episode?.id) Streams(state, torrents, onAction, onStream, Modifier.padding(start = d.gutter + 16.dp, end = d.gutter))
                    }
                }
            } else {
                item { SectionTitle(stringResource(R.string.addon_streams)) }
                item { Streams(state, torrents, onAction, onStream, Modifier.padding(horizontal = d.gutter)) }
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

/**
 * The streams, by add-on like Nuvio: a row of chips (All, then each add-on with its count) and a
 * refresh button, then each add-on's streams under its name. Add-ons appear as they answer; the
 * ones that found nothing or did not answer are named at the end, not hidden.
 */
@Composable
private fun Streams(state: CatalogTitleUiState, torrents: Boolean, onAction: (CatalogTitleAction) -> Unit, onStream: (AddonStream) -> Unit, modifier: Modifier) {
    Column(modifier.padding(vertical = 8.dp).testTag("streams"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.noStreamAddons -> Note(stringResource(R.string.addon_no_stream_addons))
            state.streams.isEmpty() && state.loadingStreams -> Loading()
            else -> {
                StreamTabs(state, onAction)
                val shown = state.shownStreams
                if (shown.isEmpty() && !state.loadingStreams) Note(stringResource(R.string.addon_no_streams))
                shown.forEach { g ->
                    GroupHeader(g)
                    if (g.loading) Loading()
                    g.streams.forEach { s -> StreamRow(s, torrents, onStream) }
                }
                if (state.streamAddon == null || shown.none { it.id == state.streamAddon }) Missing(state.streams)
            }
        }
    }
}

/** All · n, then one chip per add-on (a spinner on those still loading), and the refresh button. */
@Composable
private fun StreamTabs(state: CatalogTitleUiState, onAction: (CatalogTitleAction) -> Unit) {
    val tabs = state.streamTabs
    val chosen = state.streamAddon?.takeIf { id -> tabs.any { it.id == id } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tabs.isNotEmpty()) {
                StreamChip(stringResource(R.string.addon_streams_all, tabs.sumOf { it.streams.size }), chosen == null, loading = false, tag = "stream-addon:all") {
                    onAction(CatalogTitleAction.SelectStreamAddon(null))
                }
            }
            tabs.forEach { g ->
                val label = if (g.loading) g.addon else stringResource(R.string.addon_streams_chip, g.addon, g.streams.size)
                StreamChip(label, chosen == g.id, g.loading, "stream-addon:${g.id}") { onAction(CatalogTitleAction.SelectStreamAddon(g.id)) }
            }
        }
        IconButton(
            onClick = { onAction(CatalogTitleAction.RefreshStreams) }, enabled = !state.loadingStreams,
            modifier = Modifier.focusHighlight().testTag("streams-refresh"),
        ) {
            if (state.loadingStreams) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.addon_streams_refresh))
        }
    }
}

@Composable
private fun StreamChip(label: String, selected: Boolean, loading: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label, maxLines = 1) },
        leadingIcon = when {
            loading -> ({ CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) })
            selected -> ({ Icon(Icons.Filled.Check, contentDescription = null) })
            else -> null
        },
        modifier = Modifier.focusHighlight(MaterialTheme.shapes.small).testTag(tag),
    )
}

/** An add-on's name over its streams, with how many it found. */
@Composable
private fun GroupHeader(g: StreamGroup) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { heading() }.testTag("stream-group:${g.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(g.addon, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (!g.loading) {
            Text(pluralStringResource(R.plurals.addon_streams_count, g.streams.size, g.streams.size), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Add-ons that found nothing, and those that did not answer, by name. */
@Composable
private fun Missing(groups: List<StreamGroup>) {
    val done = groups.filter { !it.loading && it.streams.isEmpty() }
    val none = done.filter { it.error == null }.map { it.addon }
    val failed = done.filter { it.error != null }.map { it.addon }
    if (none.isEmpty() && failed.isEmpty()) return
    Column(Modifier.padding(top = 8.dp).testTag("streams-missing"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val style = MaterialTheme.typography.bodySmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        if (none.isNotEmpty()) Text(stringResource(R.string.addon_streams_none_from, none.joinToString(", ")), style = style, color = color)
        if (failed.isNotEmpty()) Text(stringResource(R.string.addon_streams_no_answer, failed.joinToString(", ")), style = style, color = color)
    }
}

@Composable
private fun StreamRow(s: AddonStream, torrents: Boolean, onStream: (AddonStream) -> Unit) {
    // Torrents stay tappable while turned off: the tap asks to turn them on (TorrentConsentDialog).
    val usable = s.playable || s.externalUrl != null || s.isTorrent
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
                    s.isTorrent && torrents -> Text(stringResource(R.string.addon_stream_torrent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    s.isTorrent -> Text(stringResource(R.string.addon_stream_torrent_off), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.externalUrl != null -> Text(stringResource(R.string.addon_stream_external), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text(stringResource(R.string.addon_stream_unsupported), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (s.playable || (s.isTorrent && torrents)) Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_play))
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
