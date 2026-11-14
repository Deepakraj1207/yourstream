package dev.yourstream.ui.screens.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.media.api.LocalMediaSources
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceKind
import dev.yourstream.media.api.MediaIndexRepository
import dev.yourstream.media.api.model.IndexState
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.ui.core.LocalDimens
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One local source with what its last scan found. */
data class LocalSourceRow(
    val source: LocalSource,
    val files: Int = 0,
    val state: IndexState = IndexState.IDLE,
    val lastScanAt: Long? = null,
    val error: String? = null,
)

data class LocalFilesUiState(
    val rows: List<LocalSourceRow> = emptyList(),
    /** Scans run only while signed in to Telegram (the library belongs to the account). */
    val signedIn: Boolean = true,
) {
    val hasDeviceVideos: Boolean get() = rows.any { it.source.kind == LocalSourceKind.DEVICE_VIDEOS }
}

sealed interface LocalFilesAction {
    data class AddFolder(val treeUri: String, val title: String) : LocalFilesAction
    data class AddDeviceVideos(val title: String) : LocalFilesAction
    data class Remove(val id: Long) : LocalFilesAction
    data object Rescan : LocalFilesAction
}

@HiltViewModel
class LocalFilesViewModel @Inject constructor(
    private val sources: LocalMediaSources,
    index: MediaIndexRepository,
    auth: TelegramAuthManager,
) : ViewModel() {
    val state: StateFlow<LocalFilesUiState> = combine(sources.sources, index.observeChats(), auth.state) { list, chats, a ->
        val byId = chats.associateBy { it.chatId }
        LocalFilesUiState(
            rows = list.map { s ->
                val c = byId[s.id]
                LocalSourceRow(s, c?.mediaCount ?: 0, c?.state ?: IndexState.IDLE, c?.lastIndexedAt, c?.lastError?.takeIf { c.state == IndexState.FAILED })
            },
            signedIn = a == AuthState.Ready,
        )
    }.catch { emit(LocalFilesUiState()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalFilesUiState())

    fun onAction(action: LocalFilesAction) {
        viewModelScope.launch {
            runCatching {
                when (action) {
                    is LocalFilesAction.AddFolder -> sources.addFolder(action.treeUri, action.title)
                    is LocalFilesAction.AddDeviceVideos -> sources.addDeviceVideos(action.title)
                    is LocalFilesAction.Remove -> sources.remove(action.id)
                    LocalFilesAction.Rescan -> sources.rescanAll()
                }
            }
        }
    }
}

@Composable
fun LocalFilesRoute(onBack: () -> Unit, vm: LocalFilesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deviceTitle = stringResource(R.string.local_device_videos)
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onAction(LocalFilesAction.AddFolder(uri.toString(), folderName(context, uri)))
    }
    // Full access, or (Android 14+) access to the videos the user selects: either works, MediaStore
    // then lists what was allowed.
    val askVideos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) vm.onAction(LocalFilesAction.AddDeviceVideos(deviceTitle))
    }
    LocalFilesScreen(
        state, onBack = onBack,
        onAddFolder = { pickFolder.launch(null) },
        onAddDeviceVideos = { askVideos.launch(videoPermissions()) },
        onAction = vm::onAction,
    )
}

/** What "Add all videos" asks for on this Android version. */
private fun videoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** The picked folder's display name (e.g. "Movies"), else the last part of its address. */
private fun folderName(context: android.content.Context, tree: Uri): String = runCatching {
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/').orEmpty().ifEmpty { "Folder" }

/**
 * Settings → Content → Files on this device: folders picked with the system picker (only those
 * folders become readable, no permission needed) and, optionally, every video the system knows
 * (asks for the video permission). Files are read on the device; nothing is uploaded.
 */
@Composable
fun LocalFilesScreen(
    state: LocalFilesUiState,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onAddDeviceVideos: () -> Unit,
    onAction: (LocalFilesAction) -> Unit,
) {
    val now = System.currentTimeMillis()
    ScreenScaffold(stringResource(R.string.local_title), onBack = onBack) {
        Column(
            Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = LocalDimens.current.gutter, vertical = 8.dp).testTag("local-files"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.local_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.signedIn) {
                Text(stringResource(R.string.local_signed_out), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("local-signed-out"))
            }
            if (state.rows.isEmpty()) {
                Text(stringResource(R.string.local_none), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("local-none"))
            }
            state.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().testTag("local:${row.source.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.source.title, style = MaterialTheme.typography.titleSmall)
                        Text(rowDetail(row, now), style = MaterialTheme.typography.bodySmall,
                            color = if (row.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onAction(LocalFilesAction.Remove(row.source.id)) }, modifier = Modifier.focusHighlight().testTag("local-remove:${row.source.id}")) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.local_remove, row.source.title))
                    }
                }
            }
            Button(onClick = onAddFolder, modifier = Modifier.focusHighlight().testTag("local-add-folder")) { Text(stringResource(R.string.local_add_folder)) }
            if (!state.hasDeviceVideos) {
                OutlinedButton(onClick = onAddDeviceVideos, modifier = Modifier.focusHighlight().testTag("local-add-device")) { Text(stringResource(R.string.local_add_device)) }
                Text(stringResource(R.string.local_add_device_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.rows.isNotEmpty() && state.signedIn) {
                OutlinedButton(onClick = { onAction(LocalFilesAction.Rescan) }, modifier = Modifier.focusHighlight().testTag("local-rescan")) { Text(stringResource(R.string.local_rescan)) }
            }
            Text(stringResource(R.string.local_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rowDetail(row: LocalSourceRow, now: Long): String = when {
    row.error != null -> row.error
    row.state == IndexState.SCANNING || row.state == IndexState.PROCESSING || row.state == IndexState.QUEUED -> stringResource(R.string.local_scanning)
    row.lastScanAt == null -> stringResource(R.string.local_not_scanned)
    else -> pluralStringResource(R.plurals.local_videos, row.files, row.files) + " · " +
        DateUtils.getRelativeTimeSpanString(row.lastScanAt, now, DateUtils.MINUTE_IN_MILLIS)
}
