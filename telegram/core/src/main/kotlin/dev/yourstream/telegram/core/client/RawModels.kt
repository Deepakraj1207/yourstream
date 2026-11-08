package dev.yourstream.telegram.core.client

/** Slices of TDLib objects needed to build [dev.yourstream.telegram.api.model.TelegramChatDetails]. */
data class TgSupergroup(
    val id: Long,
    val username: String?,
    val memberCount: Int,
    val isChannel: Boolean,
    val isForum: Boolean,
    /** Epoch seconds the supergroup was created or the user joined it. */
    val date: Long,
)

data class TgSupergroupFullInfo(
    val description: String,
    val memberCount: Int,
    val linkedChatId: Long,
)

data class TgBasicGroup(
    val id: Long,
    val memberCount: Int,
    val isActive: Boolean,
    val upgradedToSupergroupId: Long,
)

data class TgBasicGroupFullInfo(val description: String)

data class TgUserFullInfo(val bio: String?)
