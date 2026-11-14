package dev.yourstream.core.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Name resolution that gets around DNS blocks.
 *
 * Some networks answer the system DNS for certain hosts with nothing, or with a block page's
 * address (TMDB on several Indian ISPs is the known case). Hosts are first resolved the normal way;
 * when that fails, or a connection to the answer fails ([Retry]), the host is resolved through
 * DNS over HTTPS (Cloudflare, then Google, reached by IP address) and remembered for 30 minutes.
 * With [always] set (Settings), every host is resolved that way. With [fallback] off and [always]
 * off, only the system DNS is used: Cloudflare and Google never see a host name.
 */
object SecureDns {
    /** Settings → "Secure DNS": resolve every host through DNS over HTTPS. */
    @Volatile var always: Boolean = false

    /** Settings → "Secure DNS for blocked sites": use DNS over HTTPS when the system DNS fails. */
    @Volatile var fallback: Boolean = true

    private const val REMEMBER_MS = 30 * 60_000L
    private val viaDoh = ConcurrentHashMap<String, Long>()

    private val bootstrap: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    private val providers: List<Dns> by lazy {
        listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"),
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"),
        )
    }

    private fun doh(url: String, vararg ips: String): Dns = DnsOverHttps.Builder()
        .client(bootstrap)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
        .includeIPv6(true)
        .build()

    /** Hosts resolved through DNS over HTTPS right now (for diagnostics). */
    fun securedHosts(now: Long = System.currentTimeMillis()): Set<String> = viaDoh.filterValues { it > now }.keys

    internal fun useSecure(host: String, now: Long = System.currentTimeMillis()) { viaDoh[host] = now + REMEMBER_MS }

    private fun secured(host: String, now: Long = System.currentTimeMillis()) = always || (viaDoh[host] ?: 0L) > now

    /** The [Dns] for every client of the app: [fallback] when the normal lookup fails or the host is secured. */
    val dns: Dns = Resolver(Dns.SYSTEM) { host -> secureLookup(host) }

    private fun secureLookup(host: String): List<InetAddress> {
        var last: Exception? = null
        for (p in providers) {
            try {
                return p.lookup(host)
            } catch (e: Exception) {
                last = e
            }
        }
        throw UnknownHostException("$host: no secure DNS answer (${last?.message})")
    }

    /**
     * System first; on failure, or for secured hosts, the secure lookup. Addresses no public host
     * has (0.0.0.0, loopback) count as a block (pure; tested with fakes).
     */
    class Resolver(private val system: Dns, private val secure: (String) -> List<InetAddress>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (secured(hostname)) return secure(hostname)
            val answer = try {
                system.lookup(hostname)
            } catch (e: UnknownHostException) {
                if (!fallback) throw e
                useSecure(hostname)
                return runCatching { secure(hostname) }.getOrElse { throw e }
            }
            if (fallback && answer.isNotEmpty() && answer.all { it.isAnyLocalAddress || it.isLoopbackAddress } && !isLocalName(hostname)) {
                useSecure(hostname)
                return runCatching { secure(hostname) }.getOrDefault(answer)
            }
            return answer
        }

        private fun isLocalName(host: String) = host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")
    }

    /**
     * A request whose connection fails (refused, reset, TLS handshake with a block page's address)
     * is tried once more with its host resolved through DNS over HTTPS. Add to clients that use [dns].
     */
    class Retry : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = request.url.host
            return try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (chain.call().isCanceled() || !fallback || secured(host) || isLiteral(host)) throw e
                useSecure(host)
                try {
                    chain.proceed(request)
                } catch (second: IOException) {
                    second.addSuppressed(e)
                    throw second
                }
            }
        }

        private fun isLiteral(host: String) = host.all { it.isDigit() || it == '.' } || host.contains(':')
    }
}
