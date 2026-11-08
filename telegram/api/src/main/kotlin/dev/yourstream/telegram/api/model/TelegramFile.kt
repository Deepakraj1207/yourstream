package dev.yourstream.telegram.api.model

/**
 * A Telegram file and how much of it is on the device.
 *
 * [id] is local to the current TDLib session: never persist it. Persist [remoteUniqueId]
 * (plus chat and message ids) instead.
 */
public data class TelegramFile(
    val id: Int,
    val remoteUniqueId: String,
    /** Exact size in bytes, or 0 if unknown. */
    val size: Long,
    val expectedSize: Long,
    /** Absolute local path once any part is downloaded, else null. */
    val localPath: String?,
    val canBeDownloaded: Boolean,
    val isDownloadingActive: Boolean,
    val isDownloadingCompleted: Boolean,
    /** Start of the contiguous downloaded window. */
    val downloadOffset: Long,
    /** Bytes available from [downloadOffset]. */
    val downloadedPrefixSize: Long,
    val downloadedSize: Long,
)
