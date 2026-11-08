package dev.yourstream.telegram.api.model

/** Which Telegram message content carried the file. */
public enum class TelegramMediaContentType {
    /** Sent as a video (Telegram knows duration and dimensions). */
    VIDEO,
    /** Sent as a file. MKV movies, subtitles and split archives usually arrive this way. */
    DOCUMENT,
    /** Sent as music/audio. */
    AUDIO,
}

/** Telegram's server-side search filters used to enumerate media in a chat. */
public enum class MediaSearchFilter { VIDEO, DOCUMENT, AUDIO }

/** The file inside a media message plus what Telegram knows about it. */
public data class TelegramMediaFile(
    val file: TelegramFile,
    val fileName: String,
    val mimeType: String,
    /** Seconds; null when Telegram does not know (documents). */
    val durationSeconds: Int?,
    val width: Int?,
    val height: Int?,
    val supportsStreaming: Boolean,
    /** Video codec when Telegram reports it (e.g. "h264", "h265", "av1"), else null. */
    val codec: String?,
    /** Audio tags. */
    val title: String?,
    val performer: String?,
    val thumbnailFileId: Int?,
)

public data class TelegramMediaMessage(
    val chatId: Long,
    val messageId: Long,
    /** Epoch seconds. */
    val date: Long,
    /** Epoch seconds, 0 if never edited. */
    val editDate: Long,
    /** Non-zero when the message is part of an album (several files sent together). */
    val albumId: Long,
    val caption: String,
    val contentType: TelegramMediaContentType,
    val media: TelegramMediaFile,
)

/** One page of a media search, newest first. */
public data class MediaMessagePage(
    val messages: List<TelegramMediaMessage>,
    /** Pass as fromMessageId for the next (older) page; 0 means there is no more. */
    val nextFromMessageId: Long,
    /** Telegram's estimate of all matches in the chat, -1 if unknown. */
    val totalCount: Int,
)

/** Result of looking up a known message id again (deletion / change detection). */
public sealed interface MessageLookup {
    public val messageId: Long

    public data class Found(val message: TelegramMediaMessage) : MessageLookup {
        override val messageId: Long get() = message.messageId
    }

    /** The message exists but no longer carries a supported file (edited away). */
    public data class NoMedia(override val messageId: Long) : MessageLookup

    /** The message is gone. */
    public data class Missing(override val messageId: Long) : MessageLookup
}

public sealed interface TelegramMessageEvent {
    public val chatId: Long

    /** A new message with a supported file arrived (live, while the app runs). */
    public data class NewMediaMessage(val message: TelegramMediaMessage) : TelegramMessageEvent {
        override val chatId: Long get() = message.chatId
    }

    /** Messages were deleted on the server ([isPermanent]), not just evicted from TDLib's cache. */
    public data class MessagesDeleted(override val chatId: Long, val messageIds: List<Long>) : TelegramMessageEvent

    /** A message's content changed (file replaced, caption edited). Look it up again. */
    public data class MessageChanged(override val chatId: Long, val messageId: Long) : TelegramMessageEvent
}
