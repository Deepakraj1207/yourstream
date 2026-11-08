package dev.yourstream.telegram.core

import dev.yourstream.telegram.core.client.TelegramEvent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultTelegramUserRepositoryTest {

    @Test
    fun currentUserFollowsLoginAndUpdates() = runTest {
        val env = TelegramTestEnv(this)
        env.manager.start()
        advanceUntilIdle()
        assertNull(env.users.currentUser.value)

        env.auth.submitPhoneNumber("+4917012345678")
        advanceUntilIdle()
        env.auth.submitCode("12345")
        advanceUntilIdle()
        assertEquals("Ada", env.users.currentUser.value?.firstName)

        env.client.emit(TelegramEvent.UserChanged(FakeTelegramClient.user(42, "Ada Lovelace")))
        env.client.emit(TelegramEvent.UserChanged(FakeTelegramClient.user(7, "Someone else")))
        advanceUntilIdle()
        assertEquals("Ada Lovelace", env.users.currentUser.value?.firstName)

        env.auth.logOut()
        advanceUntilIdle()
        assertNull(env.users.currentUser.value)
    }
}
