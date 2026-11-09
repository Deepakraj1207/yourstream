package dev.yourstream.catalog.api

/** Rules for the links users paste for add-ons (pure; tested). */
object AddonLinks {
    /**
     * Plain http:// is accepted only for add-ons on the user's own network (a self-hosted add-on
     * on a PC or NAS): localhost, private IPv4 ranges, Tailscale/CGNAT, link-local and local names.
     * Everything on the internet must use https://.
     */
    fun isLocalHttp(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return false
        val authority = url.substring(7).substringBefore('/').substringBefore('?').substringAfterLast('@')
        val host = (if (authority.startsWith("[")) authority.substringBefore(']').removePrefix("[") else authority.substringBefore(':')).lowercase()
        if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        val p = host.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 127 || p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254)
    }
}
