package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.language.LanguageCatalog
import dev.yourstream.playback.tracks.LanguagePreferences
import dev.yourstream.playback.tracks.SubtitleMode
import dev.yourstream.playback.tracks.TrackPreferenceStore
import dev.yourstream.ui.core.ChipOption
import dev.yourstream.ui.core.ChipRow
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.SectionHeader
import dev.yourstream.ui.core.StateContent
import dev.yourstream.ui.core.UiState
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Preferred audio/subtitle languages: what the playback layer's track selection uses. */
data class LanguagesUiState(val prefs: UiState<LanguagePreferences> = UiState.Loading, val choices: List<String> = CHOICES) {
    companion object { val CHOICES = listOf("ta", "te", "hi", "ml", "kn", "bn", "mr", "en", "ja", "ko") }
}

sealed interface LanguagesAction {
    /** Moves [code] to the front of the audio preference list. */
    data class PreferAudio(val code: String) : LanguagesAction
    data class PreferSubtitles(val code: String) : LanguagesAction
    data class SetSubtitleMode(val mode: SubtitleMode) : LanguagesAction
}

@HiltViewModel
class LanguagesViewModel @Inject constructor(private val store: TrackPreferenceStore) : ViewModel() {
    private val _state = MutableStateFlow(LanguagesUiState())
    val state: StateFlow<LanguagesUiState> = _state.asStateFlow()

    init { viewModelScope.launch { _state.value = LanguagesUiState(UiState.Content(store.preferences())) } }

    fun onAction(action: LanguagesAction) {
        val p = (_state.value.prefs as? UiState.Content)?.value ?: return
        val updated = when (action) {
            is LanguagesAction.PreferAudio -> p.copy(audio = listOf(action.code) + (p.audio - action.code))
            is LanguagesAction.PreferSubtitles -> p.copy(subtitles = listOf(action.code) + (p.subtitles - action.code))
            is LanguagesAction.SetSubtitleMode -> p.copy(subtitleMode = action.mode)
        }
        _state.value = _state.value.copy(prefs = UiState.Content(updated))
        viewModelScope.launch { store.setPreferences(updated) }
    }
}

@Composable
fun LanguagesRoute(onBack: () -> Unit, vm: LanguagesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LanguagesScreen(state, vm::onAction, onBack)
}

@Composable
fun LanguagesScreen(state: LanguagesUiState, onAction: (LanguagesAction) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.settings_languages), onBack = onBack) {
        StateContent(state.prefs) { p ->
            val options = state.choices.map { ChipOption(it, LanguageCatalog.displayName(it)) }
            Column(Modifier.testTag("languages")) {
                SectionHeader(stringResource(R.string.languages_audio))
                ChipRow(options, p.audio.firstOrNull(), { onAction(LanguagesAction.PreferAudio(it)) })
                SectionHeader(stringResource(R.string.languages_subtitles))
                ChipRow(options, p.subtitles.firstOrNull(), { onAction(LanguagesAction.PreferSubtitles(it)) })
                SectionHeader(stringResource(R.string.languages_subtitle_mode))
                ChipRow(
                    listOf(ChipOption(SubtitleMode.FOREIGN_ONLY, stringResource(R.string.subtitle_mode_foreign)), ChipOption(SubtitleMode.ALWAYS, stringResource(R.string.subtitle_mode_always)),
                        ChipOption(SubtitleMode.OFF, stringResource(R.string.subtitle_mode_off))),
                    p.subtitleMode, { onAction(LanguagesAction.SetSubtitleMode(it)) },
                )
            }
        }
    }
}
