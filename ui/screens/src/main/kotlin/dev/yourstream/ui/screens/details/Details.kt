package dev.yourstream.ui.screens.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.library.api.LibraryItemDetails
import dev.yourstream.library.api.LibraryKind
import dev.yourstream.library.api.MediaRepository
import dev.yourstream.library.api.PlaybackTarget
import dev.yourstream.ui.core.ErrorKind
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.screens.Presentation
import dev.yourstream.ui.screens.navigation.DetailsRoute
import dev.yourstream.ui.screens.navigation.OpenTarget
import dev.yourstream.watch.core.NextAction
import dev.yourstream.watch.engine.WatchIntelligence
import dev.yourstream.watch.engine.WatchSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The main button. Which one it is comes from the domain's watch summary / next-episode decision. */
sealed interface PrimaryAction {
    val target: OpenTarget.Play
    data class Play(override val target: OpenTarget.Play) : PrimaryAction
    data class Resume(override val target: OpenTarget.Play, val positionMs: Long) : PrimaryAction
    /** Series: the next episode (resume or start), e.g. "S01E06". */
    data class Episode(override val target: OpenTarget.Play, val label: String, val resume: Boolean) : PrimaryAction
}

data class VersionRow(
    val mediaId: Long, val name: String, val detail: String, val target: OpenTarget.Play,
    /** Fully downloaded by Telegram on this device: plays without a connection. */
    val onDevice: Boolean = false,
    /** A file on this device (local folder): nothing to download. */
    val isLocal: Boolean = false,
)
data class SeasonRow(val seasonId: Long, val name: String, val episodes: Int)

data class DetailsContent(
    val itemId: Long,
    val kind: LibraryKind,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val languages: List<String>,
    val isFavorite: Boolean,
    /** 0..100, from the domain's watch summary. */
    val percent: Int,
    val primary: PrimaryAction?,
    val versions: List<VersionRow>,
    val seasons: List<SeasonRow>,
    /** In "My library" (the Library tab). */
    val inLibrary: Boolean = false,
    /** A file of it is still in Telegram. False: every file was deleted (no Play; progress kept). */
    val available: Boolean = true,
    /** Matched with an online catalogue (true), not (false), or not applicable (null: personal videos). */
    val matched: Boolean? = null,
)

data class DetailsUiState(
    val content: UiState<DetailsContent> = UiState.Loading,
    /** Downloads running now: media id → fraction done. */
    val downloading: Map<Long, Float> = emptyMap(),
)

sealed interface DetailsAction {
    data object ToggleFavorite : DetailsAction
    data object ToggleLibrary : DetailsAction
    data object Retry : DetailsAction
    /** Save this version on the device to watch offline. */
    data class Download(val mediaId: Long) : DetailsAction
    data class CancelDownload(val mediaId: Long) : DetailsAction
    /** Delete the downloaded copy (the video stays in Telegram). */
    data class RemoveDownload(val mediaId: Long) : DetailsAction
}

@HiltViewModel
class DetailsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val media: MediaRepository,
    private val watch: WatchIntelligence,
    private val downloads: dev.yourstream.playback.media3.VideoDownloads,
) : ViewModel() {
    private val itemId = saved.toRoute<DetailsRoute>().itemId
    private val _state = MutableStateFlow(DetailsUiState())
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            var before = emptyMap<Long, Float>()
            downloads.progress.collect { now ->
                _state.value = _state.value.copy(downloading = now)
                // A download finished (or stopped): the version's "On this device" may have changed.
                if ((before.keys - now.keys).isNotEmpty()) load(showLoading = false)
                before = now
            }
        }
    }

    fun onAction(action: DetailsAction) {
        when (action) {
            DetailsAction.Retry -> load()
            DetailsAction.ToggleFavorite -> {
                val c = (state.value.content as? UiState.Content)?.value ?: return
                viewModelScope.launch { media.setFavorite(itemId, !c.isFavorite); load(showLoading = false) }
            }
            DetailsAction.ToggleLibrary -> {
                val c = (state.value.content as? UiState.Content)?.value ?: return
                viewModelScope.launch { media.setInLibrary(itemId, !c.inLibrary); load(showLoading = false) }
            }
            is DetailsAction.Download -> viewModelScope.launch { downloads.start(PlaybackTarget(itemId), action.mediaId) }
            is DetailsAction.CancelDownload -> viewModelScope.launch { downloads.cancel(PlaybackTarget(itemId), action.mediaId) }
            is DetailsAction.RemoveDownload -> viewModelScope.launch { downloads.remove(PlaybackTarget(itemId), action.mediaId); load(showLoading = false) }
        }
    }

    private fun load(showLoading: Boolean = true) = viewModelScope.launch {
        if (showLoading) _state.value = _state.value.copy(content = UiState.Loading)
        val loaded: UiState<DetailsContent> = runCatching {
            val details = media.getItem(itemId) ?: return@runCatching UiState.Error(UiError(ErrorKind.NOT_FOUND, retryable = false))
            UiState.Content(content(details, watch.summary(itemId)))
        }.getOrElse { UiState.Error(it.toUiError()) }
        // Read the state only now: download progress may have changed while loading.
        _state.value = _state.value.copy(content = loaded)
    }

    companion object {
        /** Pure mapping (tested). */
        fun content(d: LibraryItemDetails, w: WatchSummary?): DetailsContent {
            val s = d.summary
            val primary: PrimaryAction? = if (s.kind == LibraryKind.SERIES) {
                w?.next?.takeIf { it.action != NextAction.NONE }?.episode?.let { e ->
                    PrimaryAction.Episode(OpenTarget.Play(PlaybackTarget(s.id, e.episodeId), e.lastMediaId), e.label, resume = w.next?.action == NextAction.RESUME)
                }
            } else if (s.isAvailable) {
                val position = w?.positionMs ?: 0
                if (position > 0) PrimaryAction.Resume(OpenTarget.Play(PlaybackTarget(s.id)), position) else PrimaryAction.Play(OpenTarget.Play(PlaybackTarget(s.id)))
            } else null
            return DetailsContent(
                itemId = s.id, kind = s.kind, title = s.metadataTitle ?: s.title, year = s.year, posterUrl = dev.yourstream.ui.screens.Presentation.artwork(s),
                languages = d.languages.map(LanguageCatalog::displayName), isFavorite = s.isFavorite, inLibrary = s.inLibrary, percent = w?.percent ?: 0,
                primary = primary,
                available = s.isAvailable,
                matched = if (s.kind == LibraryKind.VIDEO) null else s.metadataTitle != null,
                versions = d.versions.filter { !it.isDeleted && !it.isExcluded }.map { v ->
                    VersionRow(v.mediaId, v.fileName,
                        listOfNotNull(Presentation.resolution(v.resolution), v.quality, "%.1f GB".format(v.sizeBytes / 1e9), v.languages.joinToString("/").ifEmpty { null }).joinToString(" · "),
                        OpenTarget.Play(PlaybackTarget(s.id), v.mediaId),
                        onDevice = v.downloadState == dev.yourstream.media.api.model.DownloadState.COMPLETE.name,
                        isLocal = v.isLocal)
                },
                seasons = d.seasons.map { SeasonRow(it.id, it.displayName, it.episodeCount) },
            )
        }
    }
}
