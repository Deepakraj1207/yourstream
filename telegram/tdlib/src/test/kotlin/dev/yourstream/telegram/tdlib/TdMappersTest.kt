package dev.yourstream.telegram.tdlib

import dev.g000sha256.tdl.dto.AuthenticationCodeInfo
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsWord
import dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListFolder
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatPosition
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.ConnectionStateConnecting
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.LocalFile
import dev.g000sha256.tdl.dto.NetworkTypeMobileRoaming
import dev.g000sha256.tdl.dto.NetworkTypeWiFi
import dev.g000sha256.tdl.dto.RemoteFile
import dev.g000sha256.tdl.dto.UpdateAuthorizationState
import dev.g000sha256.tdl.dto.UpdateChatPosition
import dev.g000sha256.tdl.dto.UpdateChatReadInbox
import dev.g000sha256.tdl.dto.UpdateChatTitle
import dev.g000sha256.tdl.dto.UpdateConnectionState
import dev.g000sha256.tdl.dto.UpdateFile
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.ChatKind
import dev.yourstream.telegram.api.model.ChatListType
import dev.yourstream.telegram.api.model.CodeDeliveryType
import dev.yourstream.telegram.api.model.NetworkType
import dev.yourstream.telegram.api.model.TelegramConnectionState
import dev.yourstream.telegram.core.client.TelegramEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TdMappersTest {

    @Test
    fun authorizationStates() {
        assertEquals(AuthState.WaitParameters, TdMappers.authState(AuthorizationStateWaitTdlibParameters()))
        assertEquals(AuthState.WaitPhoneNumber, TdMappers.authState(AuthorizationStateWaitPhoneNumber()))
        assertEquals(AuthState.Ready, TdMappers.authState(AuthorizationStateReady()))
        assertEquals(AuthState.Closed, TdMappers.authState(AuthorizationStateClosed()))
        assertEquals(
            AuthState.WaitPassword("my hint", hasRecoveryEmail = true, recoveryEmailPattern = "a***@b.c"),
            TdMappers.authState(AuthorizationStateWaitPassword("my hint", true, false, "a***@b.c")),
        )
        assertEquals(
            AuthState.WaitOtherDeviceConfirmation("tg://login?token=x"),
            TdMappers.authState(AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=x")),
        )
    }

    @Test
    fun waitCodeCarriesDeliveryTypesAndLength() {
        val state = TdMappers.authState(
            AuthorizationStateWaitCode(
                AuthenticationCodeInfo("+49170", AuthenticationCodeTypeTelegramMessage(5), AuthenticationCodeTypeCall(5), 45),
            ),
        )
        val info = assertIs<AuthState.WaitCode>(state).info
        assertEquals(CodeDeliveryType.TELEGRAM_MESSAGE, info.type)
        assertEquals(CodeDeliveryType.CALL, info.nextType)
        assertEquals(45, info.timeoutSeconds)
        assertEquals(5, info.codeLength)
    }

    @Test
    fun wordCodesHaveNoLength() {
        val info = TdMappers.codeInfo(AuthenticationCodeInfo("+1", AuthenticationCodeTypeSmsWord("a"), null, 0))
        assertEquals(CodeDeliveryType.SMS_WORD, info.type)
        assertNull(info.codeLength)
        assertNull(info.nextType)
    }

    @Test
    fun chatTypesSplitChannelsFromGroups() {
        assertEquals(ChatKind.Private(7), TdMappers.chatKind(ChatTypePrivate(7)))
        assertEquals(ChatKind.Secret(3, 7), TdMappers.chatKind(ChatTypeSecret(3, 7)))
        assertEquals(ChatKind.BasicGroup(9), TdMappers.chatKind(ChatTypeBasicGroup(9)))
        assertEquals(ChatKind.Supergroup(11), TdMappers.chatKind(ChatTypeSupergroup(11, isChannel = false)))
        assertEquals(ChatKind.Channel(11), TdMappers.chatKind(ChatTypeSupergroup(11, isChannel = true)))
    }

    @Test
    fun chatListsRoundTrip() {
        assertEquals(ChatListType.Main, TdMappers.chatList(ChatListMain()))
        assertEquals(ChatListType.Archive, TdMappers.chatList(ChatListArchive()))
        assertEquals(ChatListType.Folder(4), TdMappers.chatList(ChatListFolder(4)))
        assertEquals(ChatListType.Folder(4), TdMappers.chatList(TdMappers.tdChatList(ChatListType.Folder(4))))
    }

    @Test
    fun connectionStates() {
        assertEquals(TelegramConnectionState.READY, TdMappers.connectionState(ConnectionStateReady()))
        assertEquals(TelegramConnectionState.CONNECTING, TdMappers.connectionState(ConnectionStateConnecting()))
        assertEquals(TelegramConnectionState.WAITING_FOR_NETWORK, TdMappers.connectionState(ConnectionStateWaitingForNetwork()))
    }

    @Test
    fun networkTypes() {
        assertIs<NetworkTypeWiFi>(TdMappers.tdNetworkType(NetworkType.WIFI))
        assertIs<NetworkTypeMobileRoaming>(TdMappers.tdNetworkType(NetworkType.MOBILE_ROAMING))
    }

    @Test
    fun filesKeepTheDownloadWindow() {
        val file = TdMappers.file(
            File(
                id = 5, size = 1000, expectedSize = 1000,
                local = LocalFile("", true, false, true, false, 200, 300, 300),
                remote = RemoteFile("rid", "uniq", false, true, 1000),
            ),
        )
        assertEquals("uniq", file.remoteUniqueId)
        assertEquals("rid", file.remoteId)
        assertNull(file.localPath)
        assertEquals(200, file.downloadOffset)
        assertEquals(300, file.downloadedPrefixSize)
    }

    @Test
    fun updatesBecomeEvents() {
        assertEquals(
            TelegramEvent.AuthStateChanged(AuthState.Ready),
            TdMappers.event(UpdateAuthorizationState(AuthorizationStateReady())),
        )
        assertEquals(
            TelegramEvent.ConnectionStateChanged(TelegramConnectionState.READY),
            TdMappers.event(UpdateConnectionState(ConnectionStateReady())),
        )
        assertEquals(TelegramEvent.ChatTitleChanged(1, "New"), TdMappers.event(UpdateChatTitle(1, "New")))
        assertEquals(TelegramEvent.ChatUnreadCountChanged(1, 3), TdMappers.event(UpdateChatReadInbox(1, 99, 3)))
        val position = assertIs<TelegramEvent.ChatPositionChanged>(
            TdMappers.event(UpdateChatPosition(1, ChatPosition(ChatListMain(), 500, true, null))),
        )
        assertEquals(500, position.position.order)
        assertEquals(true, position.position.isPinned)
        assertIs<TelegramEvent.FileChanged>(
            TdMappers.event(
                UpdateFile(File(1, 1, 1, LocalFile("", true, false, false, false, 0, 0, 0), RemoteFile("", "", false, true, 1))),
            ),
        )
    }
}
