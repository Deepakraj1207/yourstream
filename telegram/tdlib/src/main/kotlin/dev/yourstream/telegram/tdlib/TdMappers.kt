package dev.yourstream.telegram.tdlib

import dev.g000sha256.tdl.dto.AuthenticationCodeInfo
import dev.g000sha256.tdl.dto.AuthenticationCodeType
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFirebaseAndroid
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFirebaseIos
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFlashCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeFragment
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeMissedCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsPhrase
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsWord
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.BasicGroup
import dev.g000sha256.tdl.dto.BasicGroupFullInfo
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatList
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListFolder
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatType
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.ConnectionStateConnecting
import dev.g000sha256.tdl.dto.ConnectionStateConnectingToProxy
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.NetworkTypeMobile
import dev.g000sha256.tdl.dto.NetworkTypeMobileRoaming
import dev.g000sha256.tdl.dto.NetworkTypeNone
import dev.g000sha256.tdl.dto.NetworkTypeOther
import dev.g000sha256.tdl.dto.NetworkTypeWiFi
import dev.g000sha256.tdl.dto.Supergroup
import dev.g000sha256.tdl.dto.SupergroupFullInfo
import dev.g000sha256.tdl.dto.Update
import dev.g000sha256.tdl.dto.UpdateAuthorizationState
import dev.g000sha256.tdl.dto.UpdateChatLastMessage
import dev.g000sha256.tdl.dto.UpdateChatPosition
import dev.g000sha256.tdl.dto.UpdateChatReadInbox
import dev.g000sha256.tdl.dto.UpdateChatTitle
import dev.g000sha256.tdl.dto.UpdateConnectionState
import dev.g000sha256.tdl.dto.UpdateFile
import dev.g000sha256.tdl.dto.UpdateNewChat
import dev.g000sha256.tdl.dto.UpdateUser
import dev.g000sha256.tdl.dto.User
import dev.g000sha256.tdl.dto.UserFullInfo
import dev.g000sha256.tdl.dto.UserTypeBot
import dev.g000sha256.tdl.dto.UserTypeDeleted
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.ChatPosition
import dev.yourstream.telegram.api.model.CodeDeliveryType
import dev.yourstream.telegram.api.model.CodeInfo
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.api.model.TelegramChat
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.api.model.TelegramFile
import dev.yourstream.telegram.api.model.TelegramUser
import dev.yourstream.telegram.core.client.TelegramEvent
import dev.yourstream.telegram.core.client.TgBasicGroup
import dev.yourstream.telegram.core.client.TgBasicGroupFullInfo
import dev.yourstream.telegram.core.client.TgSupergroup
import dev.yourstream.telegram.core.client.TgSupergroupFullInfo
import dev.yourstream.telegram.core.client.TgUserFullInfo
import dev.g000sha256.tdl.dto.ChatPosition as TdChatPosition
import dev.g000sha256.tdl.dto.NetworkType as TdNetworkType

/** TDLib DTO <-> app model translation. Pure functions; the only place DTO shapes matter. */
internal object TdMappers {

    fun authState(state: AuthorizationState): AuthState = when (state) {
        is AuthorizationStateWaitTdlibParameters -> AuthState.WaitParameters
        is AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhoneNumber
        is AuthorizationStateWaitCode -> AuthState.WaitCode(codeInfo(state.codeInfo))
        is AuthorizationStateWaitPassword -> AuthState.WaitPassword(
            hint = state.passwordHint,
            hasRecoveryEmail = state.hasRecoveryEmailAddress,
            recoveryEmailPattern = state.recoveryEmailAddressPattern,
        )
        is AuthorizationStateWaitEmailAddress -> AuthState.WaitEmailAddress
        is AuthorizationStateWaitEmailCode -> AuthState.WaitEmailCode(
            emailPattern = state.codeInfo.emailAddressPattern,
            codeLength = state.codeInfo.length,
        )
        is AuthorizationStateWaitOtherDeviceConfirmation -> AuthState.WaitOtherDeviceConfirmation(state.link)
        is AuthorizationStateWaitRegistration -> AuthState.WaitRegistration
        is AuthorizationStateReady -> AuthState.Ready
        is AuthorizationStateLoggingOut -> AuthState.LoggingOut
        is AuthorizationStateClosing -> AuthState.Closing
        is AuthorizationStateClosed -> AuthState.Closed
        else -> AuthState.Unsupported(state::class.simpleName ?: "unknown")
    }

    fun codeInfo(info: AuthenticationCodeInfo): CodeInfo = CodeInfo(
        phoneNumber = info.phoneNumber,
        type = codeType(info.type),
        nextType = info.nextType?.let(::codeType),
        timeoutSeconds = info.timeout,
        codeLength = codeLength(info.type),
    )

    fun codeType(type: AuthenticationCodeType): CodeDeliveryType = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> CodeDeliveryType.TELEGRAM_MESSAGE
        is AuthenticationCodeTypeSms -> CodeDeliveryType.SMS
        is AuthenticationCodeTypeSmsWord -> CodeDeliveryType.SMS_WORD
        is AuthenticationCodeTypeSmsPhrase -> CodeDeliveryType.SMS_PHRASE
        is AuthenticationCodeTypeCall -> CodeDeliveryType.CALL
        is AuthenticationCodeTypeFlashCall -> CodeDeliveryType.FLASH_CALL
        is AuthenticationCodeTypeMissedCall -> CodeDeliveryType.MISSED_CALL
        is AuthenticationCodeTypeFragment -> CodeDeliveryType.FRAGMENT
        is AuthenticationCodeTypeFirebaseAndroid, is AuthenticationCodeTypeFirebaseIos -> CodeDeliveryType.FIREBASE
        else -> CodeDeliveryType.UNKNOWN
    }

    private fun codeLength(type: AuthenticationCodeType): Int? = when (type) {
        is AuthenticationCodeTypeTelegramMessage -> type.length
        is AuthenticationCodeTypeSms -> type.length
        is AuthenticationCodeTypeCall -> type.length
        is AuthenticationCodeTypeMissedCall -> type.length
        is AuthenticationCodeTypeFragment -> type.length
        is AuthenticationCodeTypeFirebaseAndroid -> type.length
        is AuthenticationCodeTypeFirebaseIos -> type.length
        else -> null
    }?.takeIf { it > 0 }

    fun connectionState(state: ConnectionState): TelegramConnectionState = when (state) {
        is ConnectionStateWaitingForNetwork -> TelegramConnectionState.WAITING_FOR_NETWORK
        is ConnectionStateConnectingToProxy -> TelegramConnectionState.CONNECTING_TO_PROXY
        is ConnectionStateConnecting -> TelegramConnectionState.CONNECTING
        is ConnectionStateUpdating -> TelegramConnectionState.UPDATING
        is ConnectionStateReady -> TelegramConnectionState.READY
        else -> TelegramConnectionState.UNKNOWN
    }

    fun chatList(list: ChatList): ChatListType? = when (list) {
        is ChatListMain -> ChatListType.Main
        is ChatListArchive -> ChatListType.Archive
        is ChatListFolder -> ChatListType.Folder(list.chatFolderId)
        else -> null
    }

    fun tdChatList(list: ChatListType): ChatList = when (list) {
        ChatListType.Main -> ChatListMain()
        ChatListType.Archive -> ChatListArchive()
        is ChatListType.Folder -> ChatListFolder(list.folderId)
    }

    fun position(position: TdChatPosition): ChatPosition? =
        chatList(position.list)?.let { ChatPosition(it, position.order, position.isPinned) }

    fun chatKind(type: ChatType): ChatKind = when (type) {
        is ChatTypePrivate -> ChatKind.Private(type.userId)
        is ChatTypeSecret -> ChatKind.Secret(type.secretChatId, type.userId)
        is ChatTypeBasicGroup -> ChatKind.BasicGroup(type.basicGroupId)
        is ChatTypeSupergroup ->
            if (type.isChannel) ChatKind.Channel(type.supergroupId) else ChatKind.Supergroup(type.supergroupId)
        else -> error("Unknown TDLib chat type ${type::class.simpleName}")
    }

    fun chat(chat: Chat): TelegramChat = TelegramChat(
        id = chat.id,
        title = chat.title,
        kind = chatKind(chat.type),
        positions = chat.positions.mapNotNull(::position).filter { it.order != 0L },
        unreadCount = chat.unreadCount,
        lastMessageDate = chat.lastMessage?.date?.toLong()?.takeIf { it > 0 },
        hasProtectedContent = chat.hasProtectedContent,
        smallPhotoFileId = chat.photo?.small?.id,
    )

    fun user(user: User): TelegramUser = TelegramUser(
        id = user.id,
        firstName = user.firstName,
        lastName = user.lastName,
        username = user.usernames?.activeUsernames?.firstOrNull(),
        phoneNumber = user.phoneNumber,
        isPremium = user.isPremium,
        isBot = user.type is UserTypeBot,
        isDeleted = user.type is UserTypeDeleted,
    )

    fun userFullInfo(info: UserFullInfo): TgUserFullInfo = TgUserFullInfo(bio = info.bio?.text)

    fun supergroup(group: Supergroup): TgSupergroup = TgSupergroup(
        id = group.id,
        username = group.usernames?.activeUsernames?.firstOrNull(),
        memberCount = group.memberCount,
        isChannel = group.isChannel,
        isForum = group.isForum,
        date = group.date.toLong(),
    )

    fun supergroupFullInfo(info: SupergroupFullInfo): TgSupergroupFullInfo = TgSupergroupFullInfo(
        description = info.description,
        memberCount = info.memberCount,
        linkedChatId = info.linkedChatId,
    )

    fun basicGroup(group: BasicGroup): TgBasicGroup = TgBasicGroup(
        id = group.id,
        memberCount = group.memberCount,
        isActive = group.isActive,
        upgradedToSupergroupId = group.upgradedToSupergroupId,
    )

    fun basicGroupFullInfo(info: BasicGroupFullInfo): TgBasicGroupFullInfo = TgBasicGroupFullInfo(info.description)

    fun file(file: File): TelegramFile = TelegramFile(
        id = file.id,
        remoteUniqueId = file.remote.uniqueId,
        size = file.size,
        expectedSize = file.expectedSize,
        localPath = file.local.path.ifEmpty { null },
        canBeDownloaded = file.local.canBeDownloaded,
        isDownloadingActive = file.local.isDownloadingActive,
        isDownloadingCompleted = file.local.isDownloadingCompleted,
        downloadOffset = file.local.downloadOffset,
        downloadedPrefixSize = file.local.downloadedPrefixSize,
        downloadedSize = file.local.downloadedSize,
    )

    fun tdNetworkType(type: NetworkType): TdNetworkType = when (type) {
        NetworkType.NONE -> NetworkTypeNone()
        NetworkType.WIFI -> NetworkTypeWiFi()
        NetworkType.MOBILE -> NetworkTypeMobile()
        NetworkType.MOBILE_ROAMING -> NetworkTypeMobileRoaming()
        NetworkType.OTHER -> NetworkTypeOther()
    }

    /** The updates the app uses; everything else is dropped here. */
    fun event(update: Update): TelegramEvent? = when (update) {
        is UpdateAuthorizationState -> TelegramEvent.AuthStateChanged(authState(update.authorizationState))
        is UpdateConnectionState -> TelegramEvent.ConnectionStateChanged(connectionState(update.state))
        is UpdateNewChat -> TelegramEvent.ChatAdded(chat(update.chat))
        is UpdateChatTitle -> TelegramEvent.ChatTitleChanged(update.chatId, update.title)
        is UpdateChatPosition -> position(update.position)?.let { TelegramEvent.ChatPositionChanged(update.chatId, it) }
        is UpdateChatLastMessage -> TelegramEvent.ChatLastMessageChanged(
            chatId = update.chatId,
            lastMessageDate = update.lastMessage?.date?.toLong()?.takeIf { it > 0 },
            positions = update.positions.mapNotNull(::position),
        )
        is UpdateChatReadInbox -> TelegramEvent.ChatUnreadCountChanged(update.chatId, update.unreadCount)
        is UpdateUser -> TelegramEvent.UserChanged(user(update.user))
        is UpdateFile -> TelegramEvent.FileChanged(file(update.file))
        else -> null
    }
}
