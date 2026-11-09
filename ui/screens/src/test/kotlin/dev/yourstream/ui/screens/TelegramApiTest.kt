package dev.yourstream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.yourstream.telegram.api.ApiCredentials
import dev.yourstream.telegram.api.CredentialsSource
import dev.yourstream.ui.core.WindowLayout
import dev.yourstream.ui.core.YourStreamTheme
import dev.yourstream.ui.screens.settings.TelegramApiAction
import dev.yourstream.ui.screens.settings.TelegramApiScreen
import dev.yourstream.ui.screens.settings.TelegramApiUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Made-up values only: real credentials never go into tests. */
private const val FAKE_ID = 12345678
private const val FAKE_HASH = "0123456789abcdef0123456789abcdef"

class ApiCredentialsTest {
    @Test
    fun parsesWhatUsersTypeAndNeverPrintsTheHash() {
        val ok = ApiCredentials.parse(" $FAKE_ID ", " ${FAKE_HASH.uppercase()} ")
        assertEquals(ApiCredentials(FAKE_ID, FAKE_HASH), ok.credentials, "trimmed and lower-cased")
        assertFalse(ok.credentials.toString().contains(FAKE_HASH), "the hash never appears in logs")
        assertNull(ApiCredentials.parse("0", FAKE_HASH).credentials)
        assertFalse(ApiCredentials.parse("abc", FAKE_HASH).idValid)
        assertFalse(ApiCredentials.parse("1", FAKE_HASH.dropLast(1)).hashValid, "31 characters")
        assertFalse(ApiCredentials.parse("1", FAKE_HASH.dropLast(1) + "g").hashValid, "not hexadecimal")
        assertTrue(ApiCredentials.parse("1", FAKE_HASH).hashValid)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TelegramApiScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savesOnlyValidCredentialsAndConfirmsWhenSignedIn() {
        val actions = mutableListOf<TelegramApiAction>()
        var state by androidx.compose.runtime.mutableStateOf(TelegramApiUiState(CredentialsSource.NONE))
        compose.setContent { YourStreamTheme(WindowLayout.COMPACT) { TelegramApiScreen(state, { actions += it }, {}) } }
        compose.onNodeWithText("Not set yet", substring = true).assertExists()
        compose.onNodeWithTag("api-save").assertIsNotEnabled()
        compose.onNodeWithTag("api-id").performTextInput(FAKE_ID.toString())
        compose.onNodeWithTag("api-hash").performTextInput(FAKE_HASH.dropLast(1))
        compose.onNodeWithTag("api-save").assertIsNotEnabled()
        compose.onNodeWithText("An API hash has 32 characters", substring = true).assertExists()
        compose.onNodeWithTag("api-hash").performTextInput(FAKE_HASH.takeLast(1))
        compose.onNodeWithTag("api-save").assertIsEnabled().performClick()
        assertEquals(listOf<TelegramApiAction>(TelegramApiAction.Save(ApiCredentials(FAKE_ID, FAKE_HASH))), actions)

        // Signed in with the app's own credentials: removing them asks first (a new session follows).
        state = TelegramApiUiState(CredentialsSource.APP, FAKE_ID, hasBuiltIn = true, signedIn = true)
        compose.onNodeWithText("In use: your own (ID $FAKE_ID)").assertExists()
        compose.onNodeWithTag("api-remove").performClick()
        assertEquals(1, actions.size)
        compose.onNodeWithTag("api-confirm").performClick()
        assertEquals(TelegramApiAction.UseBuiltIn, actions.last())
    }
}
