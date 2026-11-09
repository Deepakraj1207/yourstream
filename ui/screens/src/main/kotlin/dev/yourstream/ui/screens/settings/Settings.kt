package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.lazy.LazyColumn
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
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.playback.tracks.TrackPreferenceStore
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.ui.core.ListRow
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The settings hub: one summary line per section. */
data class SettingsUiState(
    val signedInAs: String? = null,
    val signedIn: Boolean = false,
    val enabledSources: Int = 0,
    val languages: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    auth: TelegramAuthManager,
    users: TelegramUserRepository,
    index: MediaIndexRepository,
    private val tracks: TrackPreferenceStore,
) : ViewModel() {
    private val languages = MutableStateFlow("")

    val state: StateFlow<SettingsUiState> = combine(auth.state, users.currentUser, index.observeChats(), languages) { a, user, chats, langs ->
        SettingsUiState(
            signedInAs = user?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ").ifEmpty { it.username } },
            signedIn = a == AuthState.Ready,
            enabledSources = chats.count { it.enabled },
            languages = langs,
        )
    }.catch { emit(SettingsUiState()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Called when the hub is shown again (the languages screen may have changed them). */
    fun refresh() = viewModelScope.launch {
        val p = runCatching { tracks.preferences() }.getOrNull() ?: return@launch
        languages.value = (p.audio.take(3).map(LanguageCatalog::displayName)).joinToString(", ")
    }
}

@Composable
fun SettingsRoute(onAccount: () -> Unit, onSources: () -> Unit, onLanguages: () -> Unit, onDeveloperTools: (() -> Unit)?, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    SettingsScreen(state, onAccount, onSources, onLanguages, onDeveloperTools)
}

@Composable
fun SettingsScreen(state: SettingsUiState, onAccount: () -> Unit, onSources: () -> Unit, onLanguages: () -> Unit, onDeveloperTools: (() -> Unit)?) {
    ScreenScaffold(stringResource(R.string.nav_settings)) {
        LazyColumn(Modifier.testTag("settings")) {
            item {
                ListRow(stringResource(R.string.settings_account),
                    supporting = if (state.signedIn) stringResource(R.string.settings_signed_in_as, state.signedInAs.orEmpty()) else stringResource(R.string.settings_not_signed_in),
                    onClick = onAccount)
            }
            item { ListRow(stringResource(R.string.settings_sources), supporting = stringResource(R.string.settings_sources_count, state.enabledSources), onClick = onSources) }
            item { ListRow(stringResource(R.string.settings_languages), supporting = state.languages.ifEmpty { null }, onClick = onLanguages) }
            onDeveloperTools?.let { item { ListRow(stringResource(R.string.settings_developer), supporting = stringResource(R.string.settings_developer_detail), onClick = it) } }
        }
    }
}
