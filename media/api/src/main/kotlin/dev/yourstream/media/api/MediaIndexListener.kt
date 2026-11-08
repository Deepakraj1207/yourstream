package dev.yourstream.media.api

/**
 * Told when the media index changed (scan finished, live update applied, metadata re-parsed).
 * The library implements it to bring itself up to date. Must return quickly: implementations
 * schedule their work instead of doing it inline.
 */
fun interface MediaIndexListener {
    fun onIndexChanged()
}
