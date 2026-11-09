package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.inkPanel
import dev.yourstream.ui.core.inkUnderline
import dev.yourstream.ui.screens.player.label
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.filled.Add
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
    val updates: dev.yourstream.ui.screens.update.UpdateViewModel = hiltViewModel()
    val update by updates.state.collectAsStateWithLifecycle()
    val prefsVm: AppPreferencesViewModel = hiltViewModel()
    val prefs by prefsVm.settings.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val cleared = stringResource(R.string.settings_cache_cleared)
    SettingsScreen(
        state, appVersion, onAccount, onSources, onLanguages, more,
        updateLine = dev.yourstream.ui.screens.update.updateSummary(updates.installedVersion, update),
        onUpdate = {
            when (update) {
                is dev.yourstream.ui.screens.update.UpdateState.Available, is dev.yourstream.ui.screens.update.UpdateState.NeedsPermission -> updates.install()
                is dev.yourstream.ui.screens.update.UpdateState.Downloading, is dev.yourstream.ui.screens.update.UpdateState.Installing,
                dev.yourstream.ui.screens.update.UpdateState.Checking -> Unit
                else -> updates.check()
            }
        },
        prefs = prefs, onPrefs = { new -> prefsVm.update { new } },
        onClearCache = {
            runCatching {
                val loader = coil3.SingletonImageLoader.get(context)
                loader.memoryCache?.clear(); loader.diskCache?.clear()
            }
            android.widget.Toast.makeText(context, cleared, android.widget.Toast.LENGTH_SHORT).show()
        },
        onSourceCode = {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
    )
}

private const val SOURCE_URL = "https://github.com/Deepakraj1207/yourstream"

/** The advanced screens reachable from Settings. */
data class SettingsLinks(
    val libraryStatus: (() -> Unit)? = null,
    val review: (() -> Unit)? = null,
    val stats: (() -> Unit)? = null,
    val sync: (() -> Unit)? = null,
    val addons: (() -> Unit)? = null,
    val tmdb: (() -> Unit)? = null,
)

/**
 * Settings in the style of Stremio/Nuvio: a profile card, then rounded groups (Content, Player,
 * Appearance, Your data, About) with switches and choices right in the list. Content width is
 * capped on large screens and TV.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState, appVersion: String?, onAccount: () -> Unit, onSources: () -> Unit, onLanguages: () -> Unit,
    more: SettingsLinks = SettingsLinks(),
    /** "Version 0.4.0 · Up to date", "Update to 0.5.0"; null shows the plain version. */
    updateLine: String? = null,
    onUpdate: (() -> Unit)? = null,
    prefs: AppSettings = AppSettings(),
    onPrefs: (AppSettings) -> Unit = {},
    onClearCache: (() -> Unit)? = null,
    onSourceCode: (() -> Unit)? = null,
) {
    var picker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Picker?>(null) }
    ScreenScaffold(stringResource(R.string.nav_settings)) {
        LazyColumn(
            Modifier.testTag("settings").widthIn(max = 760.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = LocalDimens.current.gutter, vertical = 8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            item { Profile(state, onAccount) }

            item { Group(R.string.settings_group_content) }
            item {
                Card {
                    more.addons?.let { Row(stringResource(R.string.settings_addons), stringResource(R.string.settings_addons_detail), Icons.Filled.Add, onClick = it) }
                    Row(stringResource(R.string.settings_sources), pluralStringResource(R.plurals.settings_sources_count, state.enabledSources, state.enabledSources),
                        Icons.AutoMirrored.Filled.List, onClick = onSources)
                    more.tmdb?.let { Row(stringResource(R.string.tmdb_title), stringResource(R.string.settings_tmdb_detail), Icons.Filled.Info, tag = "settings-tmdb", onClick = it) }
                    more.libraryStatus?.let { Row(stringResource(R.string.settings_status), stringResource(R.string.settings_status_detail), dev.yourstream.ui.core.AppIcons.Episodes, onClick = it) }
                    more.review?.takeIf { state.reviewCount > 0 }?.let {
                        Row(stringResource(R.string.settings_review), pluralStringResource(R.plurals.settings_review_count, state.reviewCount, state.reviewCount), Icons.Filled.Warning, onClick = it)
                    }
                }
            }

            item { Group(R.string.settings_group_player) }
            item {
                Card {
                    Row(stringResource(R.string.settings_languages), state.languages.ifEmpty { null }, Icons.Filled.Settings, onClick = onLanguages)
                    Row(stringResource(R.string.settings_seek), stringResource(R.string.settings_seconds, prefs.seekSeconds), dev.yourstream.ui.core.AppIcons.FastForward,
                        tag = "pref-seek", onClick = { picker = Picker.SEEK })
                    Toggle(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_detail), Icons.Filled.PlayArrow, prefs.autoplayNext, "pref-autoplay") {
                        onPrefs(prefs.copy(autoplayNext = it))
                    }
                    Row(stringResource(R.string.settings_fit), prefs.defaultFit.label(), dev.yourstream.ui.core.AppIcons.AspectRatio,
                        tag = "pref-fit", onClick = { picker = Picker.FIT })
                    Toggle(stringResource(R.string.settings_landscape), stringResource(R.string.settings_landscape_detail), dev.yourstream.ui.core.AppIcons.ScreenRotation,
                        prefs.playerLandscape, "pref-landscape") { onPrefs(prefs.copy(playerLandscape = it)) }
                    Row(stringResource(R.string.settings_hold_speed), speedLabel(prefs.holdSpeed), dev.yourstream.ui.core.AppIcons.Speed,
                        tag = "pref-hold", onClick = { picker = Picker.HOLD })
                    Toggle(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_detail), dev.yourstream.ui.core.AppIcons.Episodes,
                        prefs.pictureInPicture, "pref-pip") { onPrefs(prefs.copy(pictureInPicture = it)) }
                    Row(stringResource(R.string.settings_subtitle_size), subtitleSizeLabel(prefs.subtitleScale), dev.yourstream.ui.core.AppIcons.Subtitles,
                        tag = "pref-subtitle-size", onClick = { picker = Picker.SUBTITLE })
                    Toggle(stringResource(R.string.settings_subtitle_box), stringResource(R.string.settings_subtitle_box_detail), dev.yourstream.ui.core.AppIcons.Subtitles,
                        prefs.subtitleBox, "pref-subtitle-box") { onPrefs(prefs.copy(subtitleBox = it)) }
                }
            }

            item { Group(R.string.settings_group_appearance) }
            item {
                Card {
                    Row(stringResource(R.string.settings_style), styleLabel(prefs.style), Icons.Filled.Star, tag = "pref-style", onClick = { picker = Picker.STYLE })
                    Row(stringResource(R.string.settings_theme), themeLabel(prefs.theme), Icons.Filled.Settings, tag = "pref-theme", onClick = { picker = Picker.THEME })
                }
            }

            if (more.stats != null || more.sync != null || onClearCache != null) {
                item { Group(R.string.settings_group_you) }
                item {
                    Card {
                        more.stats?.let { Row(stringResource(R.string.settings_stats), stringResource(R.string.settings_stats_detail), Icons.Filled.DateRange, onClick = it) }
                        more.sync?.let {
                            Row(stringResource(R.string.settings_sync), stringResource(if (state.syncOn) R.string.settings_sync_on else R.string.settings_sync_off), Icons.Filled.Refresh, onClick = it)
                        }
                        onClearCache?.let { Row(stringResource(R.string.settings_clear_cache), stringResource(R.string.settings_clear_cache_detail), Icons.Filled.Delete, onClick = it) }
                    }
                }
            }

            item { Group(R.string.settings_group_about) }
            item {
                Card {
                    Row(stringResource(R.string.app_name), appVersion?.let { stringResource(R.string.settings_version, it) } ?: stringResource(R.string.settings_about_line), Icons.Filled.Info)
                    onUpdate?.let { Row(stringResource(R.string.update_row), updateLine, dev.yourstream.ui.core.AppIcons.Update, tag = "update-row", onClick = it) }
                    onSourceCode?.let { Row(stringResource(R.string.settings_source), stringResource(R.string.settings_source_detail), Icons.Filled.Share, onClick = it) }
                }
            }
        }
    }

    picker?.let { p ->
        when (p) {
            Picker.SEEK -> Choose(stringResource(R.string.settings_seek), AppSettings.SEEK_OPTIONS.map { it to stringResource(R.string.settings_seconds, it) }, prefs.seekSeconds,
                { onPrefs(prefs.copy(seekSeconds = it)) }) { picker = null }
            Picker.FIT -> Choose(stringResource(R.string.settings_fit), dev.yourstream.ui.screens.player.VideoFit.entries.map { it to it.label() },
                prefs.defaultFit, { onPrefs(prefs.copy(defaultFit = it)) }) { picker = null }
            Picker.HOLD -> Choose(stringResource(R.string.settings_hold_speed), AppSettings.HOLD_SPEEDS.map { it to speedLabel(it) }, prefs.holdSpeed,
                { onPrefs(prefs.copy(holdSpeed = it)) }) { picker = null }
            Picker.SUBTITLE -> Choose(stringResource(R.string.settings_subtitle_size), AppSettings.SUBTITLE_SCALES.map { it to subtitleSizeLabel(it) }, prefs.subtitleScale,
                { onPrefs(prefs.copy(subtitleScale = it)) }) { picker = null }
            Picker.STYLE -> Choose(stringResource(R.string.settings_style), UiStyle.entries.map { it to styleLabel(it) }, prefs.style,
                { onPrefs(prefs.copy(style = it)) }) { picker = null }
            Picker.THEME -> Choose(stringResource(R.string.settings_theme), ThemeMode.entries.map { it to themeLabel(it) }, prefs.theme,
                { onPrefs(prefs.copy(theme = it)) }) { picker = null }
        }
    }
}

private enum class Picker { SEEK, FIT, HOLD, SUBTITLE, STYLE, THEME }

private fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "×"

@Composable
private fun subtitleSizeLabel(scale: Float) = stringResource(
    when {
        scale < 0.9f -> R.string.settings_subtitle_small
        scale < 1.1f -> R.string.settings_subtitle_normal
        scale < 1.3f -> R.string.settings_subtitle_large
        scale < 1.6f -> R.string.settings_subtitle_larger
        else -> R.string.settings_subtitle_huge
    },
)

@Composable
private fun styleLabel(s: UiStyle) = stringResource(if (s == UiStyle.MANGA) R.string.settings_style_manga else R.string.settings_style_classic)

@Composable
private fun themeLabel(t: ThemeMode) = stringResource(
    when (t) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.DARK -> R.string.settings_theme_dark
        ThemeMode.LIGHT -> R.string.settings_theme_light
    },
)

/** The Telegram account as a profile card (avatar with initials, name, state). */
@Composable
private fun Profile(state: SettingsUiState, onAccount: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onAccount, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).inkPanel(MaterialTheme.shapes.large).focusHighlight(MaterialTheme.shapes.large).testTag("profile"),
    ) {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp)) {
                androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (state.signedIn && !state.signedInAs.isNullOrBlank()) {
                        Text(dev.yourstream.ui.core.initials(state.signedInAs), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                    } else Icon(Icons.Filled.AccountCircle, null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(if (state.signedIn) state.signedInAs.orEmpty() else stringResource(R.string.settings_not_signed_in), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.signedIn) stringResource(R.string.settings_account) else stringResource(R.string.settings_sign_in_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** A rounded group of rows, like the cards in Stremio's and Nuvio's settings. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp).inkPanel(MaterialTheme.shapes.large)) {
        androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Row(title: String, supporting: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String? = null, onClick: (() -> Unit)? = null) {
    ListRow(title, supporting = supporting, leading = { Icon(icon, contentDescription = null) }, onClick = onClick,
        trailing = onClick?.let { { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun Toggle(title: String, supporting: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    ListRow(title, supporting = supporting, leading = { Icon(icon, contentDescription = null) }, onClick = { onChange(!checked) },
        trailing = { androidx.compose.material3.Switch(checked = checked, onCheckedChange = null) }, modifier = Modifier.testTag(tag))
}

/** A single-choice dialog. */
@Composable
private fun <T> Choose(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                options.forEach { (value, label) ->
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().clickable { onPick(value); onClose() }.padding(vertical = 12.dp).testTag("choice:$label"),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = value == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun Group(title: Int) {
    if (dev.yourstream.ui.core.LocalMangaStyle.current) {
        // Manga: chapter-style headings in comic lettering with an ink rule.
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp).padding(bottom = 4.dp)
                .inkUnderline(MaterialTheme.colorScheme.onSurface).semantics { heading() })
    } else {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp).semantics { heading() })
    }
}
