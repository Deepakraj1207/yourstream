package dev.yourstream.media.indexer.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yourstream.media.api.LocalSource
import dev.yourstream.media.api.LocalSourceKind
import javax.inject.Inject

/** One video file on the device, as a local source lists it. */
data class LocalFile(
    /** Stable within its source (document id / MediaStore id): the file's identity across scans. */
    val key: String,
    /** `content://` address to read it. */
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Epoch millis. */
    val modifiedAt: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** Lists the video files of a local source. Throws [SecurityException] when access was lost. */
fun interface LocalFileLister {
    fun list(source: LocalSource): List<LocalFile>
}

/**
 * Reads the system's own indexes, without opening any file: a picked folder's documents (Storage
 * Access Framework, recursively, only inside that folder) or MediaStore's video table.
 */
class AndroidLocalFileLister @Inject constructor(@ApplicationContext private val context: Context) : LocalFileLister {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun list(source: LocalSource): List<LocalFile> = when (source.kind) {
        LocalSourceKind.FOLDER -> folder(Uri.parse(source.uri ?: throw SecurityException("folder without an address")))
        LocalSourceKind.DEVICE_VIDEOS -> deviceVideos()
    }

    private fun folder(tree: Uri): List<LocalFile> {
        val out = mutableListOf<LocalFile>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val seen = HashSet<String>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (pending.isNotEmpty() && out.size < MAX_FILES) {
            val parent = pending.removeFirst()
            if (!seen.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (!name.startsWith(".")) pending += id
                        isVideo(name, mime) -> out += LocalFile(
                            key = id, uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name = name,
                            mimeType = mime.ifEmpty { "video/*" }, sizeBytes = if (c.isNull(3)) 0 else c.getLong(3),
                            modifiedAt = if (c.isNull(4)) 0 else c.getLong(4),
                        )
                    }
                }
            } ?: throw SecurityException("folder not readable")
        }
        return out
    }

    private fun deviceVideos(): List<LocalFile> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
        )
        val out = mutableListOf<LocalFile>()
        resolver.query(collection, columns, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < MAX_FILES) {
                val id = c.getLong(0)
                out += LocalFile(
                    key = id.toString(), uri = android.content.ContentUris.withAppendedId(collection, id).toString(),
                    name = c.getString(1).orEmpty(), mimeType = c.getString(2) ?: "video/*", sizeBytes = c.getLong(3),
                    modifiedAt = c.getLong(4) * 1000, durationMs = c.getLong(5).takeIf { it > 0 },
                    width = c.getInt(6).takeIf { it > 0 }, height = c.getInt(7).takeIf { it > 0 },
                )
            }
        } ?: throw SecurityException("videos not readable")
        return out
    }

    companion object {
        /** A scan stops listing here (a safety bound against a huge or looping tree). */
        const val MAX_FILES = 50_000
        private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp")

        /** By MIME type, or by extension when the provider reports none (pure; tested). */
        fun isVideo(name: String, mime: String): Boolean =
            mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    }
}
