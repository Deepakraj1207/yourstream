package dev.yourstream.media.indexer.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.core.database.YourStreamDatabase
import dev.yourstream.media.api.LocalMediaSources
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceKind
import dev.yourstream.media.api.MediaIndexListener
import dev.yourstream.media.api.MediaIndexer
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultLocalMediaSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalSourceStore,
    private val scanner: LocalScanner,
    private val indexer: MediaIndexer,
    private val db: YourStreamDatabase,
    private val listeners: Set<@JvmSuppressWildcards MediaIndexListener>,
) : LocalMediaSources {
    override val sources: StateFlow<List<LocalSource>> get() = store.sources

    override suspend fun addFolder(treeUri: String, title: String): LocalSource {
        // Keep the read access across restarts (the picker grants it for this session only).
        runCatching { context.contentResolver.takePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return add(LocalSourceKind.FOLDER, title, treeUri)
    }

    override suspend fun addDeviceVideos(title: String): LocalSource = add(LocalSourceKind.DEVICE_VIDEOS, title, null)

    private suspend fun add(kind: LocalSourceKind, title: String, uri: String?): LocalSource {
        val source = store.add(kind, title, uri)
        scanner.ensureRow(source)
        indexer.resume(source.id) // clears an old pause and queues the scan
        return source
    }

    override suspend fun remove(id: Long) {
        val source = store.get(id) ?: return
        indexer.cancel(id)
        store.remove(id)
        // Its files leave the index (cascade from the source's row) and then the library.
        db.indexedChatDao().delete(id)
        listeners.forEach { it.onIndexChanged() }
        source.uri?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    override suspend fun rescanAll() {
        store.sources.value.forEach { s ->
            scanner.ensureRow(s)
            indexer.schedule(s.id)
        }
    }
}
