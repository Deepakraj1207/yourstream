package dev.yourstream.catalog.stremio

import java.net.URLDecoder

/** What a magnet link says: the torrent's info hash (40 hex digits, lowercase), its trackers and name. */
data class Magnet(val infoHash: String, val trackers: List<String>, val name: String?)

object Magnets {
    /**
     * "magnet:?xt=urn:btih:<hex or base32>&tr=…&dn=…" → [Magnet]; null when it names no
     * BitTorrent info hash (pure; tested).
     */
    fun parse(link: String): Magnet? {
        if (!link.startsWith("magnet:", ignoreCase = true)) return null
        var hash: String? = null
        var name: String? = null
        val trackers = mutableListOf<String>()
        for (part in link.substringAfter('?', "").split('&')) {
            val key = part.substringBefore('=').lowercase()
            val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()?.trim() ?: continue
            when {
                key == "xt" && hash == null && value.startsWith("urn:btih:", ignoreCase = true) -> hash = toHex(value.substring(9))
                key == "tr" && value.isNotEmpty() -> trackers += value
                key == "dn" && value.isNotEmpty() -> name = value
            }
        }
        return hash?.let { Magnet(it, trackers.distinct(), name) }
    }

    private fun toHex(h: String): String? = when {
        h.length == 40 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> h.lowercase()
        h.length == 32 -> base32ToHex(h.uppercase())
        else -> null
    }

    /** 32 base32 characters = 160 bits = 40 hex digits. */
    private fun base32ToHex(b32: String): String? {
        var bits = 0
        var count = 0
        val out = StringBuilder(40)
        for (c in b32) {
            val v = BASE32.indexOf(c)
            if (v < 0) return null
            bits = ((bits shl 5) or v) and 0x1FF
            count += 5
            while (count >= 4) {
                count -= 4
                out.append(HEX[(bits shr count) and 0xF])
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val HEX = "0123456789abcdef"
}
