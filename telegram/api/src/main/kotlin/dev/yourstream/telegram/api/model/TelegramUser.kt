package dev.yourstream.telegram.api.model

public data class TelegramUser(
    val id: Long,
    val firstName: String,
    val lastName: String,
    /** Primary active username without "@", if any. */
    val username: String?,
    /** Personal data: show only to the account owner, never log. Empty if hidden. */
    val phoneNumber: String,
    val isPremium: Boolean,
    val isBot: Boolean,
    val isDeleted: Boolean,
) {
    val displayName: String
        get() = "$firstName $lastName".trim().ifEmpty { username?.let { "@$it" } ?: "User $id" }
}
