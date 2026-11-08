package dev.yourstream.telegram.api.model

/** TDLib's view of the network. TDLib reconnects by itself; this is for display and diagnostics. */
public enum class TelegramConnectionState {
    /** No TDLib instance running, or no report yet. */
    UNKNOWN,
    WAITING_FOR_NETWORK,
    CONNECTING_TO_PROXY,
    CONNECTING,
    /** Connected, catching up on missed updates. */
    UPDATING,
    READY,
}

/** What kind of network the device is on; fed to TDLib so it reconnects promptly. */
public enum class NetworkType { NONE, WIFI, MOBILE, MOBILE_ROAMING, OTHER }

/** Lifecycle of the TDLib instance itself (independent of login state). */
public sealed interface TelegramStatus {
    public data object Stopped : TelegramStatus
    public data class NotConfigured(val reason: String) : TelegramStatus
    public data object Starting : TelegramStatus
    public data object Running : TelegramStatus
    /** Instance closed (logout/restart); a fresh one is being created. */
    public data object Restarting : TelegramStatus
    public data class Failed(val error: dev.yourstream.core.common.AppError) : TelegramStatus
}
