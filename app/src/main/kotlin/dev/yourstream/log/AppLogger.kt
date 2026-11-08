package dev.yourstream.log

import android.util.Log
import dev.yourstream.BuildConfig
import dev.yourstream.core.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logcat plus an in-memory ring buffer the debug screen shows, so login problems can be
 * diagnosed on a device without adb. Callers never pass secrets (see [Logger]).
 */
@Singleton
class AppLogger @Inject constructor() : Logger {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, message)
        record("D", tag, message, null)
    }

    override fun i(tag: String, message: String) {
        Log.i(PREFIX + tag, message)
        record("I", tag, message, null)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(PREFIX + tag, message, throwable)
        record("W", tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(PREFIX + tag, message, throwable)
        record("E", tag, message, throwable)
    }

    private fun record(level: String, tag: String, message: String, throwable: Throwable?) {
        val stamp = synchronized(time) { time.format(Date()) }
        val line = "$stamp $level/$tag: $message" + (throwable?.let { " (${it::class.simpleName}: ${it.message})" } ?: "")
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    private companion object {
        const val PREFIX = "YS/"
        const val MAX_LINES = 300
    }
}
