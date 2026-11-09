package dev.yourstream.telegram.api

import dev.yourstream.core.common.AppResult

/** A document the user keeps in their own "Saved Messages" chat. */
public data class SavedDocument(
    val messageId: Long,
    /** Epoch millis the message was sent. */
    val date: Long,
    val fileId: Int,
    val fileName: String,
    val caption: String,
    val size: Long,
)

/**
 * The user's own Saved Messages, used as private storage for small app files (the optional,
 * end-to-end encrypted progress sync). Only documents whose caption carries the app's tag are
 * touched; nothing else in the chat is read or changed.
 */
public interface TelegramSavedMessages {
    /** Documents whose caption contains [tag], newest first. */
    public suspend fun find(tag: String, limit: Int = 10): AppResult<List<SavedDocument>>

    /** Downloads [doc] completely and returns its bytes (small files only, at most [maxBytes]). */
    public suspend fun read(doc: SavedDocument, maxBytes: Long = 8L * 1024 * 1024): AppResult<ByteArray>

    /** Sends [bytes] as a document, then deletes the older documents tagged [tag] (so one copy remains). */
    public suspend fun replace(tag: String, fileName: String, caption: String, bytes: ByteArray): AppResult<Unit>

    /** Deletes every document tagged [tag]. */
    public suspend fun delete(tag: String): AppResult<Unit>
}
