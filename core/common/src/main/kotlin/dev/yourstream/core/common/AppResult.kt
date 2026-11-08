package dev.yourstream.core.common

/**
 * Result of an operation that can fail in an expected way. Exceptions are reserved for bugs;
 * every failure a caller should handle (network, Telegram errors, bad input) is an [AppError].
 */
public sealed interface AppResult<out T> {
    public data class Ok<out T>(val value: T) : AppResult<T>
    public data class Err(val error: AppError) : AppResult<Nothing>
}

public val AppResult<*>.isOk: Boolean get() = this is AppResult.Ok

public fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Ok)?.value

public fun AppResult<*>.errorOrNull(): AppError? = (this as? AppResult.Err)?.error

public inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

public inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Ok -> transform(value)
    is AppResult.Err -> this
}

public inline fun <T> AppResult<T>.onOk(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Ok) action(value)
    return this
}

public inline fun <T> AppResult<T>.onErr(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Err) action(error)
    return this
}

public fun <T> T.ok(): AppResult<T> = AppResult.Ok(this)

public fun AppError.err(): AppResult<Nothing> = AppResult.Err(this)
