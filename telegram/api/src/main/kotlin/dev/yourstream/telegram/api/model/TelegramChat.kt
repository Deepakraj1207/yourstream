package dev.yourstream.telegram.api.model

/** A chat as the app sees it. Built from TDLib's chat object plus later updates. */
public data class TelegramChat(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    /** Where this chat sits in each chat list. No entry for a list = not in that list. */
    val positions: List<ChatPosition>,
    val unreadCount: Int,
    /** Epoch seconds of the last message, if known. */
    val lastMessageDate: Long?,
    /** Saving/forwarding is forbidden by the owner. Later phases must respect this. */
    val hasProtectedContent: Boolean,
    /** TDLib file id of the small avatar (session-local, do not persist). */
    val smallPhotoFileId: Int?,
) {
    val category: ChatCategory get() = kind.category

    public fun positionIn(list: ChatListType): ChatPosition? = positions.firstOrNull { it.list == list }
}

public sealed interface ChatKind {
    public val category: ChatCategory

    public data class Private(val userId: Long) : ChatKind {
        override val category: ChatCategory get() = ChatCategory.PRIVATE
    }

    /** End-to-end encrypted chat. Media there is device-local; listed but rarely useful. */
    public data class Secret(val secretChatId: Int, val userId: Long) : ChatKind {
        override val category: ChatCategory get() = ChatCategory.PRIVATE
    }

    public data class BasicGroup(val basicGroupId: Long) : ChatKind {
        override val category: ChatCategory get() = ChatCategory.GROUP
    }

    public data class Supergroup(val supergroupId: Long) : ChatKind {
        override val category: ChatCategory get() = ChatCategory.GROUP
    }

    public data class Channel(val supergroupId: Long) : ChatKind {
        override val category: ChatCategory get() = ChatCategory.CHANNEL
    }
}

public enum class ChatCategory { PRIVATE, GROUP, CHANNEL }

/** Which chats to show. */
public enum class ChatFilter {
    ALL, PRIVATE, GROUPS, CHANNELS;

    public fun accepts(chat: TelegramChat): Boolean = when (this) {
        ALL -> true
        PRIVATE -> chat.category == ChatCategory.PRIVATE
        GROUPS -> chat.category == ChatCategory.GROUP
        CHANNELS -> chat.category == ChatCategory.CHANNEL
    }
}

/** A Telegram chat list: the main list, the archive, or a user-defined folder. */
public sealed interface ChatListType {
    public data object Main : ChatListType
    public data object Archive : ChatListType
    public data class Folder(val folderId: Int) : ChatListType
}

/**
 * [order] sorts chats in a list, descending (TDLib semantics). 0 means "not in the list".
 */
public data class ChatPosition(val list: ChatListType, val order: Long, val isPinned: Boolean)

/** Extra information for one chat, fetched on demand. Fields are null when Telegram withholds them. */
public data class TelegramChatDetails(
    val chat: TelegramChat,
    val username: String?,
    val description: String?,
    val memberCount: Int?,
    val isForum: Boolean,
    /** Epoch seconds when the group/channel was created (or the user joined it), if known. */
    val createdAt: Long?,
    val linkedChatId: Long?,
    /** For private/secret chats: the other user. */
    val user: TelegramUser?,
)

/** A page-able, live view of one chat list. */
public data class ChatListState(
    val list: ChatListType,
    /** Sorted the way Telegram sorts them: pinned first, then by recent activity. */
    val chats: List<TelegramChat>,
    val isLoading: Boolean,
    /** False once Telegram reports every chat in this list has been loaded. */
    val hasMore: Boolean,
    val error: dev.yourstream.core.common.AppError?,
) {
    public companion object {
        public fun initial(list: ChatListType): ChatListState =
            ChatListState(list, emptyList(), isLoading = false, hasMore = true, error = null)
    }
}
