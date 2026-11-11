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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
    more: SettingsLinks = SettingsLinks(),
    /** Null: the hub; else that category's page. */
    page: SettingsPage? = null,
    onPage: (SettingsPage) -> Unit = {},
    onBack: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    val updates: dev.yourstream.ui.screens.update.UpdateViewModel = hiltViewModel()
    val update by updates.state.collectAsStateWithLifecycle()
    val prefsVm: AppPreferencesViewModel = hiltViewModel()
    val prefs by prefsVm.settings.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val cleared = stringResource(R.string.settings_cache_cleared)
    val resetDone = stringResource(R.string.settings_reset_done)
    val secured = androidx.compose.runtime.remember { dev.yourstream.core.network.SecureDns.securedHosts().sorted() }
    val host = SettingsHost(
        state, appVersion, prefs, { new -> prefsVm.update { new } }, onAccount, onSources, onLanguages, more,
        updateLine = dev.yourstream.ui.screens.update.updateSummary(updates.installedVersion, update),
        onUpdate = {
            when (update) {
                is dev.yourstream.ui.screens.update.UpdateState.Available, is dev.yourstream.ui.screens.update.UpdateState.NeedsPermission -> updates.install()
                is dev.yourstream.ui.screens.update.UpdateState.Downloading, is dev.yourstream.ui.screens.update.UpdateState.Installing,
                dev.yourstream.ui.screens.update.UpdateState.Checking -> Unit
                else -> updates.check()
            }
        },
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
        onReset = {
            prefsVm.reset()
            android.widget.Toast.makeText(context, resetDone, android.widget.Toast.LENGTH_SHORT).show()
        },
        securedHosts = secured,
    )
    if (page == null) SettingsScreen(host, onPage) else SettingsPageScreen(page, host, onBack)
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
 * Everything the settings hub and its pages show, and where their rows lead: one bundle, so the
 * hub, every page and the search results render the same rows.
 */
data class SettingsHost(
    val state: SettingsUiState = SettingsUiState(),
    val appVersion: String? = null,
    val prefs: AppSettings = AppSettings(),
    val onPrefs: (AppSettings) -> Unit = {},
    val onAccount: () -> Unit = {},
    val onSources: () -> Unit = {},
    val onLanguages: () -> Unit = {},
    val links: SettingsLinks = SettingsLinks(),
    /** "Version 0.4.0 · Up to date", "Update to 0.5.0"; null shows the plain version. */
    val updateLine: String? = null,
    val onUpdate: (() -> Unit)? = null,
    val onClearCache: (() -> Unit)? = null,
    val onSourceCode: (() -> Unit)? = null,
    val onReset: (() -> Unit)? = null,
    /** Hosts resolved through DNS over HTTPS right now (Integrations → Secure DNS). */
    val securedHosts: List<String> = emptyList(),
)

/**
 * Settings laid out like Nuvio's: a search field, the Telegram account as a profile card, then
 * sections (You, Look, Watch, Services, System) of categories, each opening its own page. While
 * searching, the matching settings are listed (and work) right here. Width is capped on large
 * screens and TV.
 */
@Composable
fun SettingsScreen(host: SettingsHost, onPage: (SettingsPage) -> Unit = {}) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var dialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SettingsDialog?>(null) }
    val rows = SettingsRows(host) { dialog = it }
    val results = searchResults(query, host)
    val pageSummaries = SettingsPage.entries.associateWith { pageSummary(it, host) }
    val noMatch = stringResource(R.string.settings_search_none, query.trim())
    ScreenScaffold(stringResource(R.string.nav_settings)) {
        SettingsList {
            item(key = "search") { SearchField(query) { query = it } }
            if (query.isBlank()) {
                item(key = "profile") { Profile(host.state, host.onAccount) }
                SettingsSection.entries.forEach { section ->
                    val pages = SettingsPage.entries.filter { it.section == section }
                    val direct = SETTINGS.filter { it.page == null && it.section == section && it.shown(host) }
                    if (pages.isEmpty() && direct.isEmpty()) return@forEach
                    item(key = "section:${section.name}") { SettingsGroup(stringResource(section.title)) }
                    item(key = "card:${section.name}") {
                        SettingsCard {
                            pages.forEach { p ->
                                SettingRow(stringResource(p.title), pageSummaries.getValue(p), p.icon, tag = "settings-page:${p.name}") { onPage(p) }
                            }
                            direct.forEach { it.row(rows) }
                        }
                    }
                }
            } else {
                results.forEach { (heading, entries) ->
                    item(key = "result-head:$heading") { SettingsGroup(heading) }
                    item(key = "result:$heading") { SettingsCard { entries.forEach { it.row(rows) } } }
                }
                if (results.isEmpty()) item(key = "none") {
                    Text(noMatch, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).testTag("settings-no-match"))
                }
            }
        }
    }
    dialog?.let { SettingsDialogs(it, host) { dialog = null } }
}

/** One category of Settings, its rows under group headings. */
@Composable
fun SettingsPageScreen(page: SettingsPage, host: SettingsHost, onBack: () -> Unit) {
    var dialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SettingsDialog?>(null) }
    val rows = SettingsRows(host) { dialog = it }
    val groups = SETTINGS.filter { it.page == page && it.shown(host) }.groupBy { it.group }
    ScreenScaffold(stringResource(page.title), onBack = onBack) {
        SettingsList {
            groups.forEach { (group, entries) ->
                item(key = "group:$group") { SettingsGroup(stringResource(group)) }
                item(key = "card:$group") { SettingsCard { entries.forEach { it.row(rows) } } }
            }
        }
    }
    dialog?.let { SettingsDialogs(it, host) { dialog = null } }
}

/** Settings matching [query] (name, group, page or keywords), grouped under their page's name. */
@Composable
private fun searchResults(query: String, host: SettingsHost): List<Pair<String, List<SettingEntry>>> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    // Resolved every time (stringResource in a fixed loop); the list is small.
    val indexed = SETTINGS.map { e ->
        val heading = e.page?.let { stringResource(it.title) } ?: stringResource(e.section.title)
        Triple(e, heading, listOf(stringResource(e.title), stringResource(e.group), heading, e.keywords).joinToString(" ").lowercase())
    }
    if (words.isEmpty()) return emptyList()
    return indexed.filter { (e, _, text) -> e.shown(host) && words.all { it in text } }
        .groupBy({ it.second }, { it.first }).toList()
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.testTag("settings").widthIn(max = 760.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = LocalDimens.current.gutter, end = LocalDimens.current.gutter, top = 8.dp,
            bottom = 8.dp + dev.yourstream.ui.core.LocalBottomClearance.current),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.material3.OutlinedTextField(
        value = query, onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) androidx.compose.material3.IconButton(onClick = { onChange("") }) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusHighlight(MaterialTheme.shapes.extraLarge).testTag("settings-search"),
    )
}

@Composable
private fun SettingsDialogs(dialog: SettingsDialog, host: SettingsHost, onClose: () -> Unit) {
    val prefs = host.prefs
    val onPrefs = host.onPrefs
    when (dialog) {
        SettingsDialog.SEEK -> Choose(stringResource(R.string.settings_seek), AppSettings.SEEK_OPTIONS.map { it to stringResource(R.string.settings_seconds, it) }, prefs.seekSeconds,
            { onPrefs(prefs.copy(seekSeconds = it)) }, onClose)
        SettingsDialog.FIT -> Choose(stringResource(R.string.settings_fit), dev.yourstream.ui.screens.player.VideoFit.entries.map { it to it.label() },
            prefs.defaultFit, { onPrefs(prefs.copy(defaultFit = it)) }, onClose)
        SettingsDialog.HOLD -> Choose(stringResource(R.string.settings_hold_speed), AppSettings.HOLD_SPEEDS.map { it to speedLabel(it) }, prefs.holdSpeed,
            { onPrefs(prefs.copy(holdSpeed = it)) }, onClose)
        SettingsDialog.SUBTITLE -> Choose(stringResource(R.string.settings_subtitle_size), AppSettings.SUBTITLE_SCALES.map { it to subtitleSizeLabel(it) }, prefs.subtitleScale,
            { onPrefs(prefs.copy(subtitleScale = it)) }, onClose)
        SettingsDialog.STYLE -> Choose(stringResource(R.string.settings_style), UiStyle.entries.map { it to styleLabel(it) }, prefs.style,
            { onPrefs(prefs.copy(style = it)) }, onClose)
        SettingsDialog.THEME -> Choose(stringResource(R.string.settings_theme), ThemeMode.entries.map { it to themeLabel(it) }, prefs.theme,
            { onPrefs(prefs.copy(theme = it)) }, onClose)
        SettingsDialog.POSTER -> Choose(stringResource(R.string.settings_poster_size), PosterSize.entries.map { it to posterSizeLabel(it) }, prefs.posterSize,
            { onPrefs(prefs.copy(posterSize = it)) }, onClose)
        SettingsDialog.BUFFER -> Choose(stringResource(R.string.settings_buffer), AppSettings.BUFFER_OPTIONS.map { it to bufferLabel(it) }, prefs.streamBufferSeconds,
            { onPrefs(prefs.copy(streamBufferSeconds = it)) }, onClose)
        SettingsDialog.RESET -> androidx.compose.material3.AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { host.onReset?.invoke(); onClose() }, modifier = Modifier.testTag("reset-confirm")) {
                    Text(stringResource(R.string.settings_reset_action))
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

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
internal fun SettingsCard(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp, bottom = 4.dp).inkPanel(MaterialTheme.shapes.large)) {
        androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
internal fun SettingRow(title: String, supporting: String?, icon: androidx.compose.ui.graphics.vector.ImageVector?, tag: String? = null, onClick: (() -> Unit)? = null) {
    ListRow(title, supporting = supporting, leading = icon?.let { { Icon(it, contentDescription = null) } }, onClick = onClick,
        trailing = onClick?.let { { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
internal fun SettingToggle(title: String, supporting: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
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
internal fun SettingsGroup(title: String) {
    if (dev.yourstream.ui.core.LocalMangaStyle.current) {
        // Manga: chapter-style headings in comic lettering with an ink rule.
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp).padding(bottom = 4.dp)
                .inkUnderline(MaterialTheme.colorScheme.onSurface).semantics { heading() })
    } else {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp).semantics { heading() })
    }
}
