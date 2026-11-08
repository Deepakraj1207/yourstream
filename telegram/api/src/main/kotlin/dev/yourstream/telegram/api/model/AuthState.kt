package dev.yourstream.telegram.api.model

/** Where the login flow is. Mirrors TDLib's authorizationState without exposing TDLib types. */
public sealed interface AuthState {
    /** Nothing reported yet (TDLib still starting). */
    public data object Unknown : AuthState

    /** API credentials are missing or invalid; nothing can happen until the build is configured. */
    public data class NotConfigured(val reason: String) : AuthState

    /** TDLib is waiting for its parameters. The manager answers this automatically. */
    public data object WaitParameters : AuthState

    public data object WaitPhoneNumber : AuthState

    public data class WaitCode(val info: CodeInfo) : AuthState

    /** Two-step verification is on for this account. */
    public data class WaitPassword(
        val hint: String,
        val hasRecoveryEmail: Boolean,
        val recoveryEmailPattern: String,
    ) : AuthState

    /** Some new logins must add a login email first. */
    public data object WaitEmailAddress : AuthState

    public data class WaitEmailCode(val emailPattern: String, val codeLength: Int) : AuthState

    /** QR login: show [link] as a QR code and scan it from a logged-in Telegram app. */
    public data class WaitOtherDeviceConfirmation(val link: String) : AuthState

    /** The number has no Telegram account. Sign-up is out of scope: use an official app first. */
    public data object WaitRegistration : AuthState

    public data object Ready : AuthState
    public data object LoggingOut : AuthState
    public data object Closing : AuthState

    /** The TDLib instance is gone (after logout or close). A new one is created automatically. */
    public data object Closed : AuthState

    /** A state this app does not handle (e.g. premium-purchase-required login). */
    public data class Unsupported(val name: String) : AuthState
}

public val AuthState.isAuthorized: Boolean get() = this == AuthState.Ready

/** True while the user is expected to type something. */
public val AuthState.isWaitingForUser: Boolean
    get() = this is AuthState.WaitPhoneNumber || this is AuthState.WaitCode ||
        this is AuthState.WaitPassword || this is AuthState.WaitEmailAddress ||
        this is AuthState.WaitEmailCode || this is AuthState.WaitOtherDeviceConfirmation

public data class CodeInfo(
    /** As Telegram formats it; treat as personal data, never log. */
    val phoneNumber: String,
    val type: CodeDeliveryType,
    val nextType: CodeDeliveryType?,
    /** Seconds until [nextType] can be requested with resendCode; 0 when unknown/none. */
    val timeoutSeconds: Int,
    /** Expected number of digits, when Telegram says. */
    val codeLength: Int?,
)

public enum class CodeDeliveryType {
    /** In-app message to another logged-in Telegram session. */
    TELEGRAM_MESSAGE,
    SMS,
    SMS_WORD,
    SMS_PHRASE,
    CALL,
    FLASH_CALL,
    MISSED_CALL,
    FRAGMENT,
    FIREBASE,
    UNKNOWN,
}
