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
import dev.yourstream.ui.core.LocalDimens
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.flow.map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    /** Titles waiting for a match check. */
    val reviewCount: Int = 0,
    /** Multi-device progress sync: on/off (it is optional and off until the user turns it on). */
    val syncOn: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    auth: TelegramAuthManager,
    users: TelegramUserRepository,
    index: MediaIndexRepository,
    private val tracks: TrackPreferenceStore,
    reviewQueue: dev.yourstream.ui.screens.ReviewQueue,
    sync: dev.yourstream.sync.ProgressSync,
) : ViewModel() {
    private val languages = MutableStateFlow("")

    private val review = reviewQueue.count()

    val state: StateFlow<SettingsUiState> = combine(auth.state, users.currentUser, index.observeChats(), languages, combine(review, sync.status) { r, s -> r to s.enabled }) { a, user, chats, langs, (r, syncOn) ->
        SettingsUiState(
            signedInAs = user?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ").ifEmpty { it.username } },
            signedIn = a == AuthState.Ready,
            enabledSources = chats.count { it.enabled },
            languages = langs,
            reviewCount = r,
            syncOn = syncOn,
        )
    }.catch { emit(SettingsUiState()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Called when the hub is shown again (the languages screen may have changed them). */
    fun refresh() = viewModelScope.launch {
        val p = runCatching { tracks.preferences() }.getOrNull() ?: return@launch
        languages.value = (p.audio.take(3).map(LanguageCatalog::displayName)).joinToString(", ")
    }
}

@Composable
fun SettingsRoute(
    appVersion: String?, onAccount: () -> Unit, onSources: () -> Unit, onLanguages: () -> Unit,
    more: SettingsLinks = SettingsLinks(), vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    SettingsScreen(state, appVersion, onAccount, onSources, onLanguages, more)
}

/** The advanced screens reachable from Settings. */
data class SettingsLinks(
    val libraryStatus: (() -> Unit)? = null,
    val review: (() -> Unit)? = null,
    val stats: (() -> Unit)? = null,
    val sync: (() -> Unit)? = null,
)

/** Grouped settings; content width is capped on large screens and TV for readability. */
@Composable
fun SettingsScreen(
    state: SettingsUiState, appVersion: String?, onAccount: () -> Unit, onSources: () -> Unit, onLanguages: () -> Unit,
    more: SettingsLinks = SettingsLinks(),
) {
    ScreenScaffold(stringResource(R.string.nav_settings)) {
        LazyColumn(
            Modifier.testTag("settings").widthIn(max = 760.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = LocalDimens.current.gutter - 16.dp, vertical = 8.dp),
        ) {
            item { Group(R.string.settings_group_library) }
            item {
                ListRow(stringResource(R.string.settings_account),
                    supporting = if (state.signedIn) stringResource(R.string.settings_signed_in_as, state.signedInAs.orEmpty()) else stringResource(R.string.settings_not_signed_in),
                    leading = { Icon(Icons.Filled.AccountCircle, contentDescription = null) }, onClick = onAccount)
            }
            item {
                ListRow(stringResource(R.string.settings_sources), supporting = pluralStringResource(R.plurals.settings_sources_count, state.enabledSources, state.enabledSources),
                    leading = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }, onClick = onSources)
            }
            more.libraryStatus?.let { open ->
                item {
                    ListRow(stringResource(R.string.settings_status), supporting = stringResource(R.string.settings_status_detail),
                        leading = { Icon(dev.yourstream.ui.core.AppIcons.Episodes, contentDescription = null) }, onClick = open)
                }
            }
            more.review?.takeIf { state.reviewCount > 0 }?.let { open ->
                item {
                    ListRow(stringResource(R.string.settings_review), supporting = pluralStringResource(R.plurals.settings_review_count, state.reviewCount, state.reviewCount),
                        leading = { Icon(Icons.Filled.Warning, contentDescription = null) }, onClick = open)
                }
            }
            item { Group(R.string.settings_group_playback) }
            item {
                ListRow(stringResource(R.string.settings_languages), supporting = state.languages.ifEmpty { null },
                    leading = { Icon(Icons.Filled.Settings, contentDescription = null) }, onClick = onLanguages)
            }
            if (more.stats != null || more.sync != null) item { Group(R.string.settings_group_you) }
            more.stats?.let { open ->
                item {
                    ListRow(stringResource(R.string.settings_stats), supporting = stringResource(R.string.settings_stats_detail),
                        leading = { Icon(Icons.Filled.DateRange, contentDescription = null) }, onClick = open)
                }
            }
            more.sync?.let { open ->
                item {
                    ListRow(stringResource(R.string.settings_sync), supporting = stringResource(if (state.syncOn) R.string.settings_sync_on else R.string.settings_sync_off),
                        leading = { Icon(Icons.Filled.Refresh, contentDescription = null) }, onClick = open)
                }
            }
            item { Group(R.string.settings_group_about) }
            item {
                ListRow(stringResource(R.string.app_name), supporting = appVersion?.let { stringResource(R.string.settings_version, it) } ?: stringResource(R.string.settings_about_line),
                    leading = { Icon(Icons.Filled.Info, contentDescription = null) })
            }
        }
    }
}

@Composable
private fun Group(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() })
}
