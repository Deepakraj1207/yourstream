package dev.yourstream.media.api

import kotlinx.coroutines.flow.StateFlow

/** What kind of local source. */
enum class LocalSourceKind {
    /** A folder the user picked (Storage Access Framework): only that folder is readable. */
    FOLDER,

    /** Every video the system media index knows (MediaStore; needs the video permission). */
    DEVICE_VIDEOS,
}

/**
 * A source of files on this device. Kept in device preferences, not the database: the choice
 * survives a Telegram sign-out (which empties the index), and the folder is scanned again after
 * the next sign-in. [uri] is the folder's tree URI (null for [LocalSourceKind.DEVICE_VIDEOS]).
 */
data class LocalSource(val id: Long, val kind: LocalSourceKind, val title: String, val uri: String?)

/**
 * Local sources share the index with Telegram chats (`indexed_chat` / `media`, source LOCAL).
 * Their ids come from a reserved range far below any Telegram chat id (users are positive,
 * groups and channels negative down to about -10^13).
 */
object LocalSourceIds {
    const val BASE: Long = -4_000_000_000_000_000_000L
    private const val SPAN: Long = 1_000_000_000L

    fun isLocal(chatId: Long): Boolean = chatId <= BASE && chatId > BASE - SPAN

    /** The [n]th local source ever added (0, 1, …): never reused, so old rows cannot be mistaken. */
    fun of(n: Long): Long {
        require(n in 0 until SPAN) { "local source number out of range: $n" }
        return BASE - n
    }
}

/** Adding, listing and removing local sources (Settings → Content → Files on this device). */
interface LocalMediaSources {
    val sources: StateFlow<List<LocalSource>>

    /** A folder picked with the system picker; [title] is its display name. Scanned right away. */
    suspend fun addFolder(treeUri: String, title: String): LocalSource

    /** All videos on the device (the caller has the video permission). Scanned right away. */
    suspend fun addDeviceVideos(title: String): LocalSource

    /** Stops scanning [id], removes its files from the library and gives back its folder access. */
    suspend fun remove(id: Long)

    /** Scan every local source again (new, changed and removed files). */
    suspend fun rescanAll()
}
