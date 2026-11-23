package dev.yourstream.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.yourstream.core.common.AppResult
import dev.yourstream.telegram.api.TelegramAuthManager
import dev.yourstream.telegram.api.model.AuthState
import dev.yourstream.telegram.api.model.CodeDeliveryType
import kotlinx.coroutines.launch

/**
 * Telegram sign-in with Telegram's own login steps (the same flow as the Android app): phone
 * number and code, two-step password, or a QR code scanned from Telegram on a phone. What the
 * user types goes to Telegram only; it is never logged or stored by the app.
 */
@Composable
fun SignInScreen(auth: TelegramAuthManager) {
    val state by auth.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // From the QR or code step, Telegram accepts a (new) phone number directly (as on Android).
    var editingPhone by remember { mutableStateOf(false) }

    fun submit(block: suspend () -> AppResult<Unit>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val r = block()
            if (r is AppResult.Err) error = r.error.message else editingPhone = false
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(420.dp)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Heading("YourStream", 30.sp)
                val phoneStep = state == AuthState.WaitPhoneNumber || (editingPhone && (state is AuthState.WaitCode || state is AuthState.WaitOtherDeviceConfirmation))
                if (phoneStep) PhoneStep(busy, onPhone = { submit { auth.submitPhoneNumber(it) } }, onQr = { submit { auth.requestQrCodeLogin() } })
                else when (val s = state) {
                    is AuthState.WaitCode -> CodeStep(s, busy, onCode = { submit { auth.submitCode(it) } }, onResend = { submit { auth.resendCode() } }, onChangeNumber = { editingPhone = true })
                    is AuthState.WaitPassword -> SecretStep(
                        title = "Two-step verification",
                        hint = if (s.hint.isNotBlank()) "Your Telegram password (hint: ${s.hint})" else "Your Telegram password",
                        busy = busy, password = true,
                    ) { submit { auth.submitPassword(it) } }
                    AuthState.WaitEmailAddress -> SecretStep("Login email", "Telegram asks for an email address for this login", busy) { submit { auth.submitEmailAddress(it) } }
                    is AuthState.WaitEmailCode -> SecretStep("Email code", "The code sent to ${s.emailPattern}", busy) { submit { auth.submitEmailCode(it) } }
                    is AuthState.WaitOtherDeviceConfirmation -> QrStep(s.link, onPhone = { editingPhone = true })
                    AuthState.WaitRegistration -> Text(
                        "This number has no Telegram account yet. Create one in the Telegram app first, then sign in here.",
                        textAlign = TextAlign.Center,
                    )
                    is AuthState.NotConfigured -> Text("Telegram is not configured in this build: ${s.reason}", textAlign = TextAlign.Center)
                    is AuthState.Unsupported -> Text("Telegram asks for a step this app cannot show (${s.name}). Sign in from the Telegram app first.", textAlign = TextAlign.Center)
                    else -> {
                        CircularProgressIndicator()
                        Text("Connecting to Telegram…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun PhoneStep(busy: Boolean, onPhone: (String) -> Unit, onQr: () -> Unit) {
    var phone by remember { mutableStateOf("") }
    Text("Sign in with your Telegram account", color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        phone, { phone = it }, label = { Text("Phone number, with country code") }, placeholder = { Text("+91 98765 43210") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (phone.isNotBlank()) onPhone(phone) }),
    )
    Button(onClick = { onPhone(phone) }, enabled = !busy && phone.count { it.isDigit() } >= 6, modifier = Modifier.fillMaxWidth()) { Text("Next") }
    TextButton(onClick = onQr, enabled = !busy) { Text("Sign in with a QR code instead") }
    Text(
        "Your videos stay in Telegram. Nothing is uploaded.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

@Composable
private fun CodeStep(s: AuthState.WaitCode, busy: Boolean, onCode: (String) -> Unit, onResend: () -> Unit, onChangeNumber: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val where = when (s.info.type) {
        CodeDeliveryType.TELEGRAM_MESSAGE -> "Telegram sent a code to your Telegram app on another device."
        CodeDeliveryType.SMS, CodeDeliveryType.SMS_WORD -> "Telegram sent a code by SMS."
        else -> "Enter the code Telegram sent you."
    }
    Text(where, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        code, { v -> code = v.filter { it.isLetterOrDigit() }.take(12) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onCode(code) }),
    )
    Button(onClick = { onCode(code) }, enabled = !busy && code.length >= 4, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
    Row {
        if (s.info.nextType != null) TextButton(onClick = onResend, enabled = !busy) { Text("Send the code another way") }
        TextButton(onClick = onChangeNumber, enabled = !busy) { Text("Change number") }
    }
}

@Composable
private fun SecretStep(title: String, hint: String, busy: Boolean, password: Boolean = false, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Text(title, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value, { value = it }, label = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSubmit(value) }),
    )
    Button(onClick = { onSubmit(value) }, enabled = !busy && value.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
}

@Composable
private fun QrStep(link: String, onPhone: () -> Unit) {
    Text("Scan with Telegram on your phone", fontWeight = FontWeight.SemiBold)
    Text(
        "Telegram → Settings → Devices → Link Desktop Device",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
    QrCode(link, Modifier.size(240.dp))
    Spacer(Modifier.height(4.dp))
    Row { TextButton(onClick = onPhone) { Text("Use a phone number instead") } }
}

/** A QR code drawn as squares (black on white, with the quiet zone QR readers need). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2)) }
    Canvas(modifier.background(Color.White, RoundedCornerShape(8.dp))) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
