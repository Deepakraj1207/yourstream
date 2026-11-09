package dev.yourstream.ui.core

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult

/**
 * The four states every screen (or section of a screen) is in. Screens render it with
 * [StateContent]; ViewModels produce it from domain results. Compose never inspects domain
 * errors directly.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    /** Loaded, nothing to show. [kind] decides the message and the call to action. */
    data class Empty(val kind: EmptyKind) : UiState<Nothing>

    data class Error(val error: UiError) : UiState<Nothing>

    /** [refreshing]: newer data is on the way (show the old content, not a spinner). */
    data class Content<T>(val value: T, val refreshing: Boolean = false) : UiState<T>
}

val <T> UiState<T>.contentOrNull: T? get() = (this as? UiState.Content<T>)?.value

inline fun <T, R> UiState<T>.map(transform: (T) -> R): UiState<R> = when (this) {
    is UiState.Content -> UiState.Content(transform(value), refreshing)
    is UiState.Empty -> this
    is UiState.Error -> this
    UiState.Loading -> UiState.Loading
}

/** Content, or [empty] when the list has nothing in it. */
fun <T> List<T>.toUiState(empty: EmptyKind): UiState<List<T>> = if (isEmpty()) UiState.Empty(empty) else UiState.Content(this)

fun <T> AppResult<T>.toUiState(empty: (T) -> EmptyKind? = { null }): UiState<T> = when (this) {
    is AppResult.Ok -> empty(value)?.let { UiState.Empty(it) } ?: UiState.Content(value)
    is AppResult.Err -> UiState.Error(error.toUiError())
}

/** Why something is empty. Each kind has its own text and, when it makes sense, an action. */
enum class EmptyKind {
    /** Telegram is not connected yet: sign in first. */
    NOT_SIGNED_IN,
    /** Signed in, but no chat is selected as a media source. */
    NO_SOURCES,
    /** Sources selected, nothing indexed (yet). */
    LIBRARY_EMPTY,
    NO_RESULTS,
    NOTHING_IN_PROGRESS,
    NO_FAVORITES,
    NO_EPISODES,
    NO_CHATS,
    GENERIC,
}

/** What the UI shows for a failure. [detail] is the domain's safe message (never secrets). */
data class UiError(val kind: ErrorKind, val detail: String? = null, val retryable: Boolean = true)

enum class ErrorKind {
    /** No network. */
    OFFLINE,
    /** Telegram is unreachable, not configured, or rejected the request. */
    TELEGRAM,
    /** Signed out / session expired. */
    SIGNED_OUT,
    /** The thing is gone (deleted message, removed item). */
    NOT_FOUND,
    /** A remote service is down or rate limiting. */
    SERVICE,
    /** The user's input was rejected (wrong code, invalid number). */
    INPUT,
    UNKNOWN,
}

/** The one place where domain errors become UI errors. */
fun AppError.toUiError(): UiError = when (this) {
    is AppError.Network -> UiError(ErrorKind.OFFLINE, message)
    is AppError.Timeout -> UiError(ErrorKind.OFFLINE, message)
    is AppError.RateLimited -> UiError(ErrorKind.SERVICE, message)
    is AppError.Unauthorized -> UiError(ErrorKind.SIGNED_OUT, message, retryable = false)
    is AppError.NotConfigured -> UiError(ErrorKind.TELEGRAM, message, retryable = false)
    is AppError.NotFound -> UiError(ErrorKind.NOT_FOUND, message, retryable = false)
    is AppError.InvalidInput -> UiError(ErrorKind.INPUT, message, retryable = false)
    is AppError.InvalidState -> UiError(ErrorKind.UNKNOWN, message, retryable = false)
    is AppError.Telegram -> UiError(ErrorKind.TELEGRAM, message)
    is AppError.Remote -> UiError(ErrorKind.SERVICE, message)
    is AppError.Unknown -> UiError(ErrorKind.UNKNOWN, message)
}

/** An unexpected exception in a ViewModel flow (should not happen; shown, never thrown). */
fun Throwable.toUiError(): UiError = UiError(ErrorKind.UNKNOWN, message ?: this::class.simpleName)
