package dev.yourstream.ui.screens.firstrun

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.MediaQuery
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.IndexingMonitor
import dev.yourstream.ui.screens.IndexingStatus
import dev.yourstream.ui.screens.R
import dev.yourstream.ui.screens.navigation.TopLevel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.NumberFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the guided first run was finished or skipped on this device. A device preference, not
 * account data: signing out keeps it (Home's setup panel guides a returning user instead).
 */
@Singleton
class FirstRunStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("first_run", Context.MODE_PRIVATE)

    val done: Boolean get() = prefs.getBoolean(KEY_DONE, false)

    fun markDone() {
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    /**
     * Show the first run? Not once it was finished or skipped, and not to someone updating from a
     * version without it who already reads chats (they are set up; it is recorded as done).
     */
    fun shouldShow(hasSources: Boolean): Boolean {
        if (done) return false
        if (hasSources) {
            markDone()
            return false
        }
        return true
    }

    private companion object {
        const val KEY_DONE = "done"
    }
}

/** The first run's steps, in order. */
enum class FirstRunStep { WELCOME, TELEGRAM, SOURCES, INDEXING, LIBRARY, PLAYBACK }

/** What the steps show about the user's setup: plain facts, never ids, hashes or provider names. */
data class FirstRunUiState(
    val signedIn: Boolean = false,
    /** Telegram is still starting: the sign-in state is not known yet. */
    val starting: Boolean = true,
    /** This build has no Telegram sign-in (no API credentials were built in). */
    val unavailable: Boolean = false,
    val name: String? = null,
    val sources: Int = 0,
    val indexing: IndexingStatus = IndexingStatus(),
    /** Video files in the index. */
    val files: Int = 0,
)

@HiltViewModel
class FirstRunViewModel @Inject constructor(
    auth: TelegramAuthManager,
    users: TelegramUserRepository,
    index: MediaIndexRepository,
    monitor: IndexingMonitor,
    private val store: FirstRunStore,
) : ViewModel() {
    val state: StateFlow<FirstRunUiState> = combine(
        auth.state, users.currentUser, index.observeChats(), monitor.status, index.count(MediaQuery()),
    ) { a, user, chats, indexing, files ->
        FirstRunUiState(
            signedIn = a == AuthState.Ready,
            starting = a == AuthState.Unknown || a == AuthState.WaitParameters,
            unavailable = a is AuthState.NotConfigured,
            name = user?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ").ifEmpty { it.username } },
            sources = chats.count { it.enabled },
            indexing = indexing,
            files = files,
        )
    }.catch { emit(FirstRunUiState(starting = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirstRunUiState())

    /** Finished or skipped: not shown again (Settings → About → Setup guide opens it). */
    fun finish() = store.markDone()
}

@Composable
fun FirstRunRoute(
    onConnectTelegram: () -> Unit,
    onChooseChats: () -> Unit,
    onDone: () -> Unit,
    vm: FirstRunViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    FirstRunScreen(state, onConnectTelegram, onChooseChats, onDone = { vm.finish(); onDone() })
}

/**
 * The guided first run, one step per screen: welcome (what the app does and what stays private),
 * connecting Telegram, choosing chats, indexing progress, the library and playback basics. Every
 * step can be skipped; "Skip" or Back on the first step ends the guide. The steps link to the
 * real sign-in and chat screens and show live progress, so nothing here is a mock-up.
 */
@Composable
fun FirstRunScreen(
    state: FirstRunUiState,
    onConnectTelegram: () -> Unit,
    onChooseChats: () -> Unit,
    onDone: () -> Unit,
    initialStep: FirstRunStep = FirstRunStep.WELCOME,
) {
    var stepIndex by rememberSaveable { mutableStateOf(initialStep.ordinal) }
    val step = FirstRunStep.entries[stepIndex]
    val last = stepIndex == FirstRunStep.entries.lastIndex
    BackHandler { if (stepIndex > 0) stepIndex-- else onDone() }

    Surface(Modifier.fillMaxSize().testTag("first-run")) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f).padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.first_run_step, stepIndex + 1, FirstRunStep.entries.size),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("first-run-progress"),
                    )
                    if (!last) TextButton(onClick = onDone, modifier = Modifier.focusHighlight().testTag("first-run-skip")) {
                        Text(stringResource(R.string.first_run_skip))
                    }
                }
                LinearProgressIndicator(
                    progress = { (stepIndex + 1f) / FirstRunStep.entries.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)
                        .testTag("first-run:${step.name}"),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (step) {
                        FirstRunStep.WELCOME -> Welcome()
                        FirstRunStep.TELEGRAM -> Telegram(state, onConnectTelegram)
                        FirstRunStep.SOURCES -> Sources(state, onChooseChats)
                        FirstRunStep.INDEXING -> Indexing(state)
                        FirstRunStep.LIBRARY -> Library()
                        FirstRunStep.PLAYBACK -> Playback()
                    }
                }
            }
            Row(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stepIndex > 0) {
                    TextButton(onClick = { stepIndex-- }, modifier = Modifier.focusHighlight().testTag("first-run-back")) {
                        Text(stringResource(R.string.first_run_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (last) onDone() else stepIndex++ },
                    modifier = Modifier.focusHighlight().testTag("first-run-next"),
                ) { Text(stringResource(nextLabel(step, state))) }
            }
        }
    }
}

/** "Next", except where the user has not done the step yet ("Not now") and at the end. */
private fun nextLabel(step: FirstRunStep, state: FirstRunUiState): Int = when {
    step == FirstRunStep.WELCOME -> R.string.first_run_start
    step == FirstRunStep.PLAYBACK -> R.string.first_run_finish
    step == FirstRunStep.TELEGRAM && !state.signedIn -> R.string.first_run_not_now
    step == FirstRunStep.SOURCES && state.sources == 0 -> R.string.first_run_not_now
    else -> R.string.first_run_next
}

@Composable
private fun StepHeader(icon: ImageVector, title: Int, body: Int) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One line with an icon: a privacy point, a tab, a playback tip. */
@Composable
private fun Point(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A status box: what is done, or what is happening now. */
@Composable
private fun Status(tag: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
private fun Welcome() {
    StepHeader(Icons.Filled.PlayArrow, R.string.first_run_welcome_title, R.string.first_run_welcome_body)
    Text(stringResource(R.string.first_run_privacy_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Point(Icons.Filled.Lock, stringResource(R.string.first_run_privacy_files))
    Point(Icons.Filled.Lock, stringResource(R.string.first_run_privacy_metadata))
    Point(Icons.Filled.Lock, stringResource(R.string.first_run_privacy_signout))
}

@Composable
private fun Telegram(state: FirstRunUiState, onConnect: () -> Unit) {
    StepHeader(Icons.Filled.Send, R.string.first_run_telegram_title, R.string.first_run_telegram_body)
    Status("first-run-telegram-status") {
        when {
            state.signedIn -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    state.name?.let { stringResource(R.string.first_run_telegram_connected_as, it) } ?: stringResource(R.string.first_run_telegram_connected),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp),
                )
            }
            state.unavailable -> Text(stringResource(R.string.first_run_telegram_unavailable), style = MaterialTheme.typography.bodyMedium)
            state.starting -> Text(stringResource(R.string.first_run_telegram_starting), style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(stringResource(R.string.first_run_telegram_how), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onConnect, modifier = Modifier.focusHighlight().testTag("first-run-connect")) {
                    Text(stringResource(R.string.first_run_telegram_connect))
                }
            }
        }
    }
}

@Composable
private fun Sources(state: FirstRunUiState, onChoose: () -> Unit) {
    StepHeader(Icons.Filled.CheckCircle, R.string.first_run_sources_title, R.string.first_run_sources_body)
    Status("first-run-sources-status") {
        if (!state.signedIn) {
            Text(stringResource(R.string.first_run_sources_sign_in_first), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                if (state.sources == 0) stringResource(R.string.first_run_sources_none)
                else pluralStringResource(R.plurals.first_run_sources_chosen, state.sources, state.sources),
                style = MaterialTheme.typography.titleMedium,
            )
            val choose = stringResource(if (state.sources == 0) R.string.first_run_sources_choose else R.string.first_run_sources_change)
            if (state.sources == 0) {
                Button(onClick = onChoose, modifier = Modifier.focusHighlight().testTag("first-run-choose")) { Text(choose) }
            } else {
                OutlinedButton(onClick = onChoose, modifier = Modifier.focusHighlight().testTag("first-run-choose")) { Text(choose) }
            }
        }
    }
}

@Composable
private fun Indexing(state: FirstRunUiState) {
    StepHeader(Icons.Filled.Refresh, R.string.first_run_indexing_title, R.string.first_run_indexing_body)
    val number = NumberFormat.getIntegerInstance()
    Status("first-run-indexing-status") {
        val s = state.indexing
        when {
            s.active -> {
                Text(pluralStringResource(R.plurals.first_run_indexing_found, s.found, number.format(s.found)), style = MaterialTheme.typography.titleMedium)
                val f = s.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.first_run_indexing_background), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.files > 0 -> Text(pluralStringResource(R.plurals.first_run_indexing_done, state.files, number.format(state.files)), style = MaterialTheme.typography.titleMedium)
            state.sources == 0 -> Text(stringResource(R.string.first_run_indexing_no_sources), style = MaterialTheme.typography.bodyMedium)
            else -> Text(stringResource(R.string.first_run_indexing_waiting), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(stringResource(R.string.first_run_indexing_controls), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Library() {
    StepHeader(TopLevel.LIBRARY.icon, R.string.first_run_library_title, R.string.first_run_library_body)
    Box(Modifier.height(4.dp))
    Point(TopLevel.HOME.icon, stringResource(R.string.first_run_tab_home))
    Point(TopLevel.MOVIES.icon, stringResource(R.string.first_run_tab_movies))
    Point(TopLevel.SEARCH.icon, stringResource(R.string.first_run_tab_search))
    Point(TopLevel.LIBRARY.icon, stringResource(R.string.first_run_tab_library))
    Point(TopLevel.SETTINGS.icon, stringResource(R.string.first_run_tab_settings))
}

@Composable
private fun Playback() {
    StepHeader(Icons.Filled.PlayArrow, R.string.first_run_playback_title, R.string.first_run_playback_body)
    Point(Icons.Filled.PlayArrow, stringResource(R.string.first_run_playback_resume))
    Point(dev.yourstream.ui.core.AppIcons.FastForward, stringResource(R.string.first_run_playback_seek))
    Point(dev.yourstream.ui.core.AppIcons.Brightness, stringResource(R.string.first_run_playback_swipe))
    Point(dev.yourstream.ui.core.AppIcons.Subtitles, stringResource(R.string.first_run_playback_tracks))
    Point(dev.yourstream.ui.core.AppIcons.Audio, stringResource(R.string.first_run_playback_background))
}
