package dev.yourstream.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the app keeps on the device that can be cleared, each with its own limit. */
enum class StorageArea {
    /** Telegram's file cache: streamed videos (bounded by StreamCache) and saved downloads. */
    TELEGRAM,
    /** Add-on streams (Media3 cache, 512 MB). */
    WEB_STREAMS,
    /** Torrent pieces (deleted when torrents stop). */
    TORRENTS,
    /** Posters and thumbnails (300 MB). */
    IMAGES,
}

/** Bytes used per area; [freeBytes]: free space on the app's storage. */
data class StorageUsage(val bytes: Map<StorageArea, Long> = emptyMap(), val freeBytes: Long? = null) {
    val total: Long get() = bytes.values.sum()
}

/** Measures and clears the app's caches (implemented in the app module, which owns them). */
interface DeviceStorage {
    suspend fun usage(): StorageUsage

    /** Clears [area]. Refuses (an error) while something plays, so playback is never cut off. */
    suspend fun clear(area: StorageArea): AppResult<Unit>
}

data class StorageUiState(
    val usage: StorageUsage? = null,
    val clearing: StorageArea? = null,
    /** The last clear: done, or why not. */
    val message: StorageMessage? = null,
)

sealed interface StorageMessage {
    data class Cleared(val area: StorageArea) : StorageMessage
    data class Failed(val area: StorageArea, val reason: String) : StorageMessage
}

@HiltViewModel
class StorageViewModel @Inject constructor(private val storage: DeviceStorage) : ViewModel() {
    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val usage = runCatching { storage.usage() }.getOrDefault(StorageUsage())
        _state.update { it.copy(usage = usage) }
    }

    fun clear(area: StorageArea) {
        if (_state.value.clearing != null) return
        _state.update { it.copy(clearing = area, message = null) }
        viewModelScope.launch {
            val result = runCatching { storage.clear(area) }.getOrElse { AppResult.Err(dev.yourstream.core.common.AppError.Unknown(it.message ?: "", it)) }
            val message = when (result) {
                is AppResult.Ok -> StorageMessage.Cleared(area)
                is AppResult.Err -> StorageMessage.Failed(area, result.error.message)
            }
            val usage = runCatching { storage.usage() }.getOrNull()
            _state.update { it.copy(clearing = null, message = message, usage = usage ?: it.usage) }
        }
    }
}

@Composable
fun StorageRoute(onBack: () -> Unit, vm: StorageViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    StorageScreen(state, vm::clear, onBack)
}

/**
 * Settings → Advanced → Storage: what each cache holds, its limit, and a button to clear it.
 * Telegram's cache also holds downloads the user saved, so clearing it asks first.
 */
@Composable
fun StorageScreen(state: StorageUiState, onClear: (StorageArea) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var confirm by rememberSaveable { mutableStateOf<StorageArea?>(null) }
    ScreenScaffold(stringResource(R.string.storage_title), onBack = onBack) {
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = LocalDimens.current.gutter, vertical = 8.dp).testTag("storage"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val usage = state.usage
            Text(
                if (usage == null) stringResource(R.string.storage_measuring)
                else stringResource(R.string.storage_total, size(usage.total)) +
                    (usage.freeBytes?.let { "\n" + stringResource(R.string.storage_free, size(it)) } ?: ""),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("storage-total"),
            )
            StorageArea.entries.forEach { area ->
                Row(Modifier.fillMaxWidth().testTag("storage:${area.name}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(area.title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            (usage?.bytes?.get(area)?.let(::size) ?: "…") + " · " + stringResource(area.detail),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { if (area == StorageArea.TELEGRAM) confirm = area else onClear(area) },
                        enabled = state.clearing == null && (usage?.bytes?.get(area) ?: 0L) > 0L,
                        modifier = Modifier.focusHighlight().testTag("storage-clear:${area.name}"),
                    ) { Text(stringResource(if (state.clearing == area) R.string.storage_clearing else R.string.storage_clear)) }
                }
            }
            state.message?.let { m ->
                Text(
                    when (m) {
                        is StorageMessage.Cleared -> stringResource(R.string.storage_cleared, stringResource(m.area.title))
                        is StorageMessage.Failed -> stringResource(R.string.storage_failed, stringResource(m.area.title), m.reason)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("storage-message"),
                )
            }
            Text(stringResource(R.string.storage_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    confirm?.let { area ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.storage_telegram_confirm_title)) },
            text = { Text(stringResource(R.string.storage_telegram_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirm = null; onClear(area) }, modifier = Modifier.testTag("storage-confirm")) { Text(stringResource(R.string.storage_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

private val StorageArea.title: Int get() = when (this) {
    StorageArea.TELEGRAM -> R.string.storage_telegram
    StorageArea.WEB_STREAMS -> R.string.storage_web
    StorageArea.TORRENTS -> R.string.storage_torrents
    StorageArea.IMAGES -> R.string.storage_images
}

private val StorageArea.detail: Int get() = when (this) {
    StorageArea.TELEGRAM -> R.string.storage_telegram_detail
    StorageArea.WEB_STREAMS -> R.string.storage_web_detail
    StorageArea.TORRENTS -> R.string.storage_torrents_detail
    StorageArea.IMAGES -> R.string.storage_images_detail
}
