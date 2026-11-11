package dev.yourstream.ui.screens.settings

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.TelegramUserRepository
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.CodeDeliveryType
import dev.yourstream.ui.core.ErrorKind
import dev.yourstream.ui.core.ErrorState
import dev.yourstream.ui.core.LoadingState
import dev.yourstream.ui.core.ScreenScaffold
import dev.yourstream.ui.core.UiError
import dev.yourstream.ui.core.toUiError
import dev.yourstream.ui.screens.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Where the Telegram sign-in is. Mirrors the domain's [AuthState]; nothing is decided here. */
sealed interface AccountStep {
    data object Starting : AccountStep
    data class NotConfigured(val reason: String) : AccountStep
    data object Phone : AccountStep
    /** [inApp]: the code went to Telegram on another device (else SMS, call, …). */
    data class Code(val phone: String, val length: Int?, val inApp: Boolean = false) : AccountStep
    data class Password(val hint: String) : AccountStep
    data object Email : AccountStep
    data class EmailCode(val pattern: String) : AccountStep
    /** Scan this link from a phone where Telegram is signed in. */
    data class Qr(val link: String) : AccountStep
    data class SignedIn(val name: String?) : AccountStep
    data object Unsupported : AccountStep
}

data class AccountUiState(
    val step: AccountStep = AccountStep.Starting,
    val busy: Boolean = false,
    /** The last request's failure, shown in plain words ([authErrorText]). */
    val error: dev.yourstream.core.common.AppError? = null,
    /** The session ended without the user signing out (another device, expiry): said on the sign-in screen. */
    val signedOutNotice: Boolean = false,
    /** Account data is being removed from the device after signing out. */
    val removingData: Boolean = false,
)

sealed interface AccountAction {
    data class Submit(val text: String) : AccountAction
    data object Resend : AccountAction
    data object UseQr : AccountAction
    /** Back to the phone number from the code or QR step (Telegram accepts a new number there). */
    data object ChangeNumber : AccountAction
    data object LogOut : AccountAction
    data object DismissNotice : AccountAction
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: TelegramAuthManager,
    users: TelegramUserRepository,
    private val account: AccountSession,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<dev.yourstream.core.common.AppError?>(null)
    private val editingPhone = MutableStateFlow(false)

    private val session = combine(account.notice, account.wiping) { n, w -> n to w }

    val state: StateFlow<AccountUiState> = combine(auth.state, users.currentUser, combine(busy, error, ::Pair), editingPhone, session) { a, u, (b, e), edit, (notice, wiping) ->
        val step = step(a, u?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ") })
        AccountUiState(if (edit && step.acceptsNewNumber()) AccountStep.Phone else step, b, e, notice, wiping)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    fun onAction(action: AccountAction) {
        if (action == AccountAction.ChangeNumber) { error.value = null; editingPhone.value = true; return }
        if (action == AccountAction.DismissNotice) { account.dismissNotice(); return }
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
                AccountAction.LogOut -> account.signOut()
                AccountAction.ChangeNumber, AccountAction.DismissNotice -> AppResult.Ok(Unit)
            }
            if (result is AppResult.Err) error.value = result.error
            else if (action is AccountAction.Submit || action == AccountAction.UseQr) editingPhone.value = false
            busy.value = false
        }
    }

    companion object {
        fun step(a: AuthState, name: String?): AccountStep = when (a) {
            AuthState.Unknown, AuthState.WaitParameters, AuthState.Closing, AuthState.Closed, AuthState.LoggingOut -> AccountStep.Starting
            is AuthState.NotConfigured -> AccountStep.NotConfigured(a.reason)
            AuthState.WaitPhoneNumber -> AccountStep.Phone
            is AuthState.WaitCode -> AccountStep.Code(a.info.phoneNumber, a.info.codeLength, a.info.type == CodeDeliveryType.TELEGRAM_MESSAGE)
            is AuthState.WaitPassword -> AccountStep.Password(a.hint)
            AuthState.WaitEmailAddress -> AccountStep.Email
            is AuthState.WaitEmailCode -> AccountStep.EmailCode(a.emailPattern)
            is AuthState.WaitOtherDeviceConfirmation -> AccountStep.Qr(a.link)
            AuthState.Ready -> AccountStep.SignedIn(name)
            AuthState.WaitRegistration, is AuthState.Unsupported -> AccountStep.Unsupported
        }

        private fun AccountStep.acceptsNewNumber() = this is AccountStep.Code || this is AccountStep.Qr || this is AccountStep.Password ||
            this == AccountStep.Email || this is AccountStep.EmailCode
    }
}

@Composable
fun AccountRoute(onBack: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    AccountScreen(state, vm::onAction, onBack)
}

/**
 * Telegram sign-in, one focused step per screen (phone number → code → password), or a QR code
 * scanned from Telegram on another device. Signed in: the account and "Sign out".
 */
@Composable
fun AccountScreen(state: AccountUiState, onAction: (AccountAction) -> Unit, onBack: () -> Unit) {
    when (val step = state.step) {
        AccountStep.Starting -> ScreenScaffold(stringResource(R.string.settings_account), onBack = onBack) {
            if (state.removingData) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.account_removing_data), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("account-removing"))
                }
            } else LoadingState()
        }
        is AccountStep.NotConfigured -> ScreenScaffold(stringResource(R.string.settings_account), onBack = onBack) {
            ErrorState(UiError(ErrorKind.TELEGRAM, stringResource(R.string.account_not_configured), retryable = false))
        }
        AccountStep.Unsupported -> ScreenScaffold(stringResource(R.string.settings_account), onBack = onBack) {
            ErrorState(UiError(ErrorKind.TELEGRAM, stringResource(R.string.account_unsupported), retryable = false))
        }
        is AccountStep.SignedIn -> ScreenScaffold(stringResource(R.string.account_signed_in_title), onBack = onBack) {
            Column(Modifier.padding(24.dp).widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_signed_in_as, step.name.orEmpty()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("signed-in"))
                Text(stringResource(R.string.account_sign_out_explained), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var confirm by remember { mutableStateOf(false) }
                OutlinedPill(stringResource(R.string.account_log_out), enabled = !state.busy, tag = "account-sign-out") { confirm = true }
                ErrorLine(state)
                if (confirm) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text(stringResource(R.string.account_sign_out_title)) },
                    text = { Text(stringResource(R.string.account_sign_out_text)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { confirm = false; onAction(AccountAction.LogOut) }, modifier = Modifier.testTag("account-sign-out-confirm")) {
                            Text(stringResource(R.string.account_log_out), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }
        }
        else -> SignInFrame(onBack) {
            when (step) {
                AccountStep.Phone -> PhoneStep(state, onAction)
                is AccountStep.Code -> CodeStep(step, state, onAction)
                is AccountStep.Password -> SecretStep(
                    stringResource(R.string.account_password_title), stringResource(R.string.account_password_intro),
                    stringResource(R.string.account_password), step.hint.takeIf { it.isNotBlank() }?.let { stringResource(R.string.account_password_hint, it) },
                    KeyboardType.Password, secret = true, state, onAction,
                )
                AccountStep.Email -> SecretStep(
                    stringResource(R.string.account_email_title), null, stringResource(R.string.account_email), null,
                    KeyboardType.Email, secret = false, state, onAction,
                )
                is AccountStep.EmailCode -> SecretStep(
                    stringResource(R.string.account_email_code_title), stringResource(R.string.account_email_code, step.pattern),
                    stringResource(R.string.account_code), null, KeyboardType.NumberPassword, secret = false, state, onAction,
                )
                is AccountStep.Qr -> QrStep(step, state, onAction)
                else -> Unit
            }
        }
    }
}

/** Brand header and a scrolling, keyboard-aware column; content width capped for tablets and TV. */
@Composable
private fun SignInFrame(onBack: () -> Unit, content: @Composable () -> Unit) {
    // Status bar inset: already consumed by the phone shell, applied here on tablets and TV.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(dev.yourstream.ui.core.R.string.ui_back))
                }
            }
            BrandHeader()
            content()
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Image(androidx.compose.ui.res.painterResource(dev.yourstream.ui.core.R.drawable.ys_logo), contentDescription = null, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun StepTitle(title: String, intro: String?) {
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    intro?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun PhoneStep(state: AccountUiState, onAction: (AccountAction) -> Unit) {
    val context = LocalContext.current
    val initial = remember { Countries.byIso(deviceCountry(context)) ?: Countries.byIso("IN") }
    // A number is personal data: kept across rotation, never persisted.
    var iso by rememberSaveable { mutableStateOf(initial?.iso) }
    var dial by rememberSaveable { mutableStateOf(initial?.dialCode.orEmpty()) }
    var number by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val country = remember(iso) { Countries.byIso(iso) }
    val numberFocus = remember { FocusRequester() }
    val valid = dial.isNotEmpty() && number.count(Char::isDigit) >= 5
    val submit = { if (valid && !state.busy) onAction(AccountAction.Submit(Countries.international(dial, number))) }

    if (state.signedOutNotice) SignedOutNotice { onAction(AccountAction.DismissNotice) }
    StepTitle(stringResource(R.string.account_phone_title), stringResource(R.string.account_phone_intro))
    Surface(
        onClick = { picking = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("account-country"),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(country?.flag.orEmpty(), fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
            Text(country?.name ?: stringResource(R.string.account_country_unknown), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.account_country))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = "+$dial",
            onValueChange = { v ->
                dial = v.filter(Char::isDigit).take(4)
                iso = Countries.forDialCode(dial, country)?.iso
            },
            label = { Text(stringResource(R.string.account_dial_code)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { numberFocus.requestFocus() }),
            modifier = Modifier.weight(0.34f).testTag("account-dial"),
        )
        OutlinedTextField(
            value = number, onValueChange = { v -> number = v.filter { it.isDigit() || it == ' ' || it == '-' }.take(20) },
            label = { Text(stringResource(R.string.account_phone)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(0.66f).focusRequester(numberFocus).testTag("account-input"),
        )
    }
    ErrorLine(state)
    Pill(stringResource(R.string.account_send_code), enabled = valid && !state.busy, busy = state.busy, tag = "account-submit", onClick = submit)
    TonalPill(stringResource(R.string.account_use_qr), enabled = !state.busy, tag = "account-qr") { onAction(AccountAction.UseQr) }

    if (picking) CountryPicker(onDismiss = { picking = false }) { c ->
        iso = c.iso; dial = c.dialCode; picking = false
        numberFocus.requestFocus()
    }
}

@Composable
private fun CodeStep(step: AccountStep.Code, state: AccountUiState, onAction: (AccountAction) -> Unit) {
    var code by remember(step) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { focus.requestFocus() } }
    StepTitle(
        stringResource(R.string.account_code_title),
        stringResource(if (step.inApp) R.string.account_code_app else R.string.account_code_sms, step.phone),
    )
    OutlinedTextField(
        value = code,
        onValueChange = { v ->
            code = v.filter(Char::isDigit).take(step.length ?: 8)
            // Telegram says how long the code is: submit as soon as it is complete.
            if (step.length != null && code.length == step.length && !state.busy) onAction(AccountAction.Submit(code))
        },
        label = { Text(stringResource(R.string.account_code)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
        textStyle = TextStyle(fontSize = 24.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.isNotEmpty()) onAction(AccountAction.Submit(code)) }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus).testTag("account-input"),
    )
    ErrorLine(state)
    Pill(stringResource(R.string.account_continue), enabled = code.isNotEmpty() && !state.busy, busy = state.busy, tag = "account-submit") { onAction(AccountAction.Submit(code)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onAction(AccountAction.ChangeNumber) }, enabled = !state.busy) { Text(stringResource(R.string.account_wrong_number)) }
        TextButton(onClick = { onAction(AccountAction.Resend) }, enabled = !state.busy) { Text(stringResource(R.string.account_resend)) }
    }
}

@Composable
private fun SecretStep(
    title: String, intro: String?, label: String, hint: String?, type: KeyboardType, secret: Boolean,
    state: AccountUiState, onAction: (AccountAction) -> Unit,
) {
    // Not saved across process death on purpose: codes and passwords are never persisted.
    var text by remember(state.step) { mutableStateOf("") }
    StepTitle(title, intro)
    OutlinedTextField(
        value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, shape = MaterialTheme.shapes.medium,
        supportingText = hint?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAction(AccountAction.Submit(text.trim())) }),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("account-input"),
    )
    ErrorLine(state)
    Pill(stringResource(R.string.account_continue), enabled = text.isNotBlank() && !state.busy, busy = state.busy, tag = "account-submit") { onAction(AccountAction.Submit(text.trim())) }
}

@Composable
private fun QrStep(step: AccountStep.Qr, state: AccountUiState, onAction: (AccountAction) -> Unit) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(step.link) { bitmap = withContext(Dispatchers.Default) { qrBitmap(step.link) } }
    val cd = stringResource(R.string.account_qr_cd)
    StepTitle(stringResource(R.string.account_qr_title), null)
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        // Always dark-on-white with a quiet zone, so any camera reads it in either theme.
        Surface(shape = MaterialTheme.shapes.large, color = Color.White, modifier = Modifier.size(248.dp).testTag("qr-link").semantics { contentDescription = cd }) {
            bitmap?.let { Image(it, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.padding(16.dp).fillMaxSize()) }
        }
    }
    listOf(R.string.account_qr_1, R.string.account_qr_2, R.string.account_qr_3).forEachIndexed { i, res ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge) }
            }
            Text(stringResource(res), style = MaterialTheme.typography.bodyLarge)
        }
    }
    ErrorLine(state)
    TonalPill(stringResource(R.string.account_use_phone), enabled = !state.busy, tag = "account-phone") { onAction(AccountAction.ChangeNumber) }
}

/** Full-screen searchable list of countries with flags and calling codes. */
@Composable
private fun CountryPicker(onDismiss: () -> Unit, onPick: (Country) -> Unit) {
    val all = remember { Countries.all() }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) {
        val q = query.trim().removePrefix("+").lowercase()
        if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.dialCode.startsWith(q) || it.iso.equals(q, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("country-picker"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.imePadding()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(dev.yourstream.ui.core.R.string.ui_back)) }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                        placeholder = { Text(stringResource(R.string.account_country_search)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("country-search"),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.iso }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 24.dp, vertical = 14.dp).testTag("country:${c.iso}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.flag, fontSize = 22.sp, modifier = Modifier.padding(end = 16.dp))
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("+${c.dialCode}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorLine(state: AccountUiState) {
    state.error?.let { Text(authErrorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("account-error")) }
}

/** Why the library is gone: the session ended without the user signing out. */
@Composable
private fun SignedOutNotice(onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("account-signed-out-notice")) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.account_signed_out_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.account_signed_out_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
        }
    }
}

/**
 * A sign-in failure in plain words: what happened and what to do. Telegram's own wording is
 * already friendly for known cases ([TelegramErrorMapper]); waits, connection problems and
 * unknown errors are phrased here.
 */
@Composable
internal fun authErrorText(e: dev.yourstream.core.common.AppError): String = when (e) {
    is dev.yourstream.core.common.AppError.RateLimited ->
        if (e.retryAfterSeconds <= 0) stringResource(R.string.account_error_wait_unknown)
        else stringResource(R.string.account_error_wait, waitLabel(e.retryAfterSeconds))
    is dev.yourstream.core.common.AppError.Network, is dev.yourstream.core.common.AppError.Timeout -> stringResource(R.string.account_error_offline)
    is dev.yourstream.core.common.AppError.Telegram -> stringResource(R.string.account_error_unexpected, e.rawMessage.ifBlank { e.code.toString() })
    is dev.yourstream.core.common.AppError.Unknown -> stringResource(R.string.account_error_unexpected, e.message)
    else -> e.message
}

@Composable
private fun waitLabel(seconds: Int): String = when {
    seconds < 90 -> pluralStringResource(R.plurals.account_wait_seconds, seconds, seconds)
    seconds < 3_600 -> ((seconds + 59) / 60).let { pluralStringResource(R.plurals.account_wait_minutes, it, it) }
    else -> ((seconds + 3599) / 3600).let { pluralStringResource(R.plurals.account_wait_hours, it, it) }
}

private val PillHeight = 56.dp

@Composable
private fun Pill(text: String, enabled: Boolean, busy: Boolean = false, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(PillHeight).testTag(tag)) {
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TonalPill(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(PillHeight).testTag(tag)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun OutlinedPill(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(PillHeight).testTag(tag)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** The SIM's or network's country, else the device locale's. */
private fun deviceCountry(context: Context): String? {
    val tm = context.getSystemService(TelephonyManager::class.java)
    return listOfNotNull(runCatching { tm?.simCountryIso }.getOrNull(), runCatching { tm?.networkCountryIso }.getOrNull(), Locale.getDefault().country)
        .firstOrNull { it.length == 2 }
}

/** A QR code as a 1-pixel-per-module bitmap (scaled up without smoothing when drawn). */
private fun qrBitmap(content: String): ImageBitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return android.graphics.Bitmap.createBitmap(pixels, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
