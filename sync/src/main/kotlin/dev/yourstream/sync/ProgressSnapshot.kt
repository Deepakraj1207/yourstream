package dev.yourstream.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Watch progress of one unit (a movie, video or episode), identified the same way on every device:
 * [file] is the Telegram file's unique id (identical for every copy of the same upload), [key] +
 * [season]/[episode] the title's match key from file-name parsing. Nothing else is shared: no
 * titles, no history, no device ids beyond a user-visible name.
 */
@Serializable
data class ProgressRecord(
    @SerialName("f") val file: String? = null,
    @SerialName("k") val key: String,
    @SerialName("s") val season: Int? = null,
    @SerialName("e") val episode: Int? = null,
    @SerialName("p") val positionMs: Long,
    @SerialName("d") val durationMs: Long,
    @SerialName("c") val completed: Boolean,
    @SerialName("n") val playCount: Int = 0,
    /** When this progress was recorded (epoch millis). The newest record of a unit wins. */
    @SerialName("t") val at: Long,
) {
    /** Unit identity for merging: the file when known, else the title and episode numbers. */
    val identity: String get() = file?.let { "f:$it" } ?: "k:$key|${season ?: ""}|${episode ?: ""}"
}

@Serializable
data class ProgressSnapshot(
    @SerialName("v") val version: Int = 1,
    /** Name of the device that wrote it ("Pixel 8"), for "last synced from". */
    @SerialName("from") val device: String,
    @SerialName("at") val at: Long,
    @SerialName("r") val records: List<ProgressRecord>,
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fun decode(bytes: ByteArray): ProgressSnapshot = json.decodeFromString(serializer(), bytes.decodeToString())
    }
}

/** Pure merge rules (tested). */
object ProgressMerge {
    /** Records kept in a snapshot (older units drop out). */
    const val MAX_RECORDS = 20_000

    /** Newer wins; on a tie, finished beats unfinished, then the further position. */
    fun newer(a: ProgressRecord, b: ProgressRecord): ProgressRecord = when {
        a.at != b.at -> if (a.at > b.at) a else b
        a.completed != b.completed -> if (a.completed) a else b
        else -> if (a.positionMs >= b.positionMs) a else b
    }

    /**
     * One record per unit, the newest of [remote] and [local]. Units a device cannot resolve (files
     * it does not have) are kept, so devices with different sources do not erase each other.
     */
    fun union(remote: List<ProgressRecord>, local: List<ProgressRecord>): List<ProgressRecord> {
        val byId = LinkedHashMap<String, ProgressRecord>()
        for (r in remote + local) byId.merge(r.identity, r, ::newer)
        return byId.values.sortedByDescending { it.at }.take(MAX_RECORDS)
    }

    /** Whether applying [incoming] would change a local state last written at [localAt]. */
    fun shouldApply(incoming: ProgressRecord, localAt: Long?): Boolean = localAt == null || incoming.at > localAt
}
