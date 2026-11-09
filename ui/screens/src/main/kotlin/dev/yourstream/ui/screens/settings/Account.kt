package dev.yourstream.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.ui.core.ErrorState
import dev.yourstream.ui.core.LoadingState
import dev.yourstream.ui.core.LocalWindowLayout
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.focusHighlight
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the Telegram sign-in is. Mirrors the domain's [AuthState]; nothing is decided here. */
sealed interface AccountStep {
    data object Starting : AccountStep
    data class NotConfigured(val reason: String) : AccountStep
    data object Phone : AccountStep
    data class Code(val phone: String, val length: Int?) : AccountStep
    data class Password(val hint: String) : AccountStep
    data object Email : AccountStep
    data class EmailCode(val pattern: String) : AccountStep
    /** Scan this link from a phone where Telegram is signed in. */
    data class Qr(val link: String) : AccountStep
    data class SignedIn(val name: String?) : AccountStep
    data object Unsupported : AccountStep
}

data class AccountUiState(val step: AccountStep = AccountStep.Starting, val busy: Boolean = false, val error: UiError? = null)

sealed interface AccountAction {
    data class Submit(val text: String) : AccountAction
    data object Resend : AccountAction
    data object UseQr : AccountAction
    data object LogOut : AccountAction
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: TelegramAuthManager,
    users: TelegramUserRepository,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<UiError?>(null)

    val state: StateFlow<AccountUiState> = combine(auth.state, users.currentUser, busy, error) { a, u, b, e ->
        AccountUiState(step(a, u?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ") }), b, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    fun onAction(action: AccountAction) {
        viewModelScope.launch {
            busy.value = true; error.value = null
            val result = when (action) {
                is AccountAction.Submit -> when (state.value.step) {
                    AccountStep.Phone -> auth.submitPhoneNumber(action.text)
                    is AccountStep.Code -> auth.submitCode(action.text)
                    is AccountStep.Password -> auth.submitPassword(action.text)
                    AccountStep.Email -> auth.submitEmailAddress(action.text)
                    is AccountStep.EmailCode -> auth.submitEmailCode(action.text)
                    else -> AppResult.Ok(Unit)
                }
                AccountAction.Resend -> auth.resendCode()
                AccountAction.UseQr -> auth.requestQrCodeLogin()
                AccountAction.LogOut -> auth.logOut()
            }
            if (result is AppResult.Err) error.value = result.error.toUiError()
            busy.value = false
        }
    }

    companion object {
        fun step(a: AuthState, name: String?): AccountStep = when (a) {
            AuthState.Unknown, AuthState.WaitParameters, AuthState.Closing, AuthState.Closed, AuthState.LoggingOut -> AccountStep.Starting
            is AuthState.NotConfigured -> AccountStep.NotConfigured(a.reason)
            AuthState.WaitPhoneNumber -> AccountStep.Phone
            is AuthState.WaitCode -> AccountStep.Code(a.info.phoneNumber, a.info.codeLength)
            is AuthState.WaitPassword -> AccountStep.Password(a.hint)
            AuthState.WaitEmailAddress -> AccountStep.Email
            is AuthState.WaitEmailCode -> AccountStep.EmailCode(a.emailPattern)
            is AuthState.WaitOtherDeviceConfirmation -> AccountStep.Qr(a.link)
            AuthState.Ready -> AccountStep.SignedIn(name)
            AuthState.WaitRegistration, is AuthState.Unsupported -> AccountStep.Unsupported
        }
    }
}

@Composable
fun AccountRoute(onBack: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    AccountScreen(state, vm::onAction, onBack)
}

@Composable
fun AccountScreen(state: AccountUiState, onAction: (AccountAction) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.settings_account), onBack = onBack) {
        when (val step = state.step) {
            AccountStep.Starting -> LoadingState()
            is AccountStep.NotConfigured -> ErrorState(UiError(dev.yourstream.ui.core.ErrorKind.TELEGRAM, step.reason, retryable = false))
            AccountStep.Unsupported -> ErrorState(UiError(dev.yourstream.ui.core.ErrorKind.TELEGRAM, stringResource(R.string.account_unsupported), retryable = false))
            else -> Column(Modifier.padding(LocalWindowLayout.current.gutter).widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    AccountStep.Phone -> Entry(stringResource(R.string.account_phone), KeyboardType.Phone, state, onAction, secondary = R.string.account_use_qr to AccountAction.UseQr)
                    is AccountStep.Code -> Entry(stringResource(R.string.account_code, step.phone), KeyboardType.NumberPassword, state, onAction, secondary = R.string.account_resend to AccountAction.Resend)
                    is AccountStep.Password -> Entry(stringResource(R.string.account_password, step.hint), KeyboardType.Password, state, onAction, secret = true)
                    AccountStep.Email -> Entry(stringResource(R.string.account_email), KeyboardType.Email, state, onAction)
                    is AccountStep.EmailCode -> Entry(stringResource(R.string.account_email_code, step.pattern), KeyboardType.NumberPassword, state, onAction)
                    is AccountStep.Qr -> {
                        Text(stringResource(R.string.account_qr))
                        Text(step.link, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("qr-link"))
                    }
                    is AccountStep.SignedIn -> {
                        Text(stringResource(R.string.settings_signed_in_as, step.name.orEmpty()), modifier = Modifier.testTag("signed-in"))
                        TextButton(onClick = { onAction(AccountAction.LogOut) }, enabled = !state.busy, modifier = Modifier.focusHighlight()) { Text(stringResource(R.string.account_log_out)) }
                    }
                    else -> Unit
                }
                state.error?.let { Text(it.detail ?: stringResource(R.string.ui_error_generic), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("account-error")) }
            }
        }
    }
}

@Composable
private fun Entry(
    label: String, type: KeyboardType, state: AccountUiState, onAction: (AccountAction) -> Unit,
    secret: Boolean = false, secondary: Pair<Int, AccountAction>? = null,
) {
    // Not saved across process death on purpose: codes and passwords are never persisted.
    var text by androidx.compose.runtime.remember(state.step) { mutableStateOf("") }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().focusHighlight().testTag("account-input"),
    )
    Button(onClick = { onAction(AccountAction.Submit(text.trim())) }, enabled = text.isNotBlank() && !state.busy, modifier = Modifier.focusHighlight().testTag("account-submit")) {
        Text(stringResource(R.string.account_continue))
    }
    secondary?.let { (label2, action) -> TextButton(onClick = { onAction(action) }, enabled = !state.busy, modifier = Modifier.focusHighlight()) { Text(stringResource(label2)) } }
}
