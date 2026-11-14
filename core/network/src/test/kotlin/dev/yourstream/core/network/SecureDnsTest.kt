package dev.yourstream.core.network

import okhttp3.Dns
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureDnsTest {
    private val real = listOf(InetAddress.getByName("13.224.0.1"))
    private val secureCalls = mutableListOf<String>()
    private fun resolver(system: Dns) = SecureDns.Resolver(system) { host -> secureCalls += host; real }

    @After fun reset() { SecureDns.always = false; SecureDns.fallback = true }

    @Test
    fun blockedLookupsGoThroughSecureDnsAndAreRemembered() {
        val blocked = Dns { throw UnknownHostException(it) }
        val r = resolver(blocked)
        assertEquals(real, r.lookup("api.themoviedb.org.test-a"))
        assertTrue("api.themoviedb.org.test-a" in SecureDns.securedHosts())
        // Remembered: the system resolver is not asked again.
        assertEquals(real, resolver(Dns { error("system asked again") }).lookup("api.themoviedb.org.test-a"))
    }

    @Test
    fun sinkholeAnswersCountAsBlocked() {
        val sinkhole = Dns { listOf(InetAddress.getByName("0.0.0.0")) }
        assertEquals(real, resolver(sinkhole).lookup("image.tmdb.org.test-b"))
        val loopback = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), resolver(loopback).lookup("localhost"), "local names may be local")
    }

    @Test
    fun workingDnsIsUsedAsIsAndAlwaysForcesSecure() {
        val normal = listOf(InetAddress.getByName("93.184.216.34"))
        assertEquals(normal, resolver(Dns { normal }).lookup("example.test-c"))
        assertTrue(secureCalls.isEmpty())
        SecureDns.always = true
        assertEquals(real, resolver(Dns { normal }).lookup("example.test-d"))
    }

    @Test
    fun whenNothingAnswersTheOriginalErrorSurfaces() {
        val r = SecureDns.Resolver(Dns { throw UnknownHostException("nope.test-e") }) { throw UnknownHostException("secure failed") }
        val e = assertFailsWith<UnknownHostException> { r.lookup("nope.test-e") }
        assertEquals("nope.test-e", e.message)
    }

    @Test
    fun withTheFallbackOffNoHostNameLeavesForCloudflareOrGoogle() {
        SecureDns.fallback = false
        val blocked = Dns { throw UnknownHostException(it) }
        assertFailsWith<UnknownHostException> { resolver(blocked).lookup("api.themoviedb.org.test-f") }
        val sinkhole = listOf(InetAddress.getByName("0.0.0.0"))
        assertEquals(sinkhole, resolver(Dns { sinkhole }).lookup("image.tmdb.org.test-g"), "the block answer is returned as is")
        assertTrue(secureCalls.isEmpty(), "no DNS-over-HTTPS lookup")
        assertTrue("api.themoviedb.org.test-f" !in SecureDns.securedHosts())
        // "Secure DNS" (always) is an explicit choice and still applies.
        SecureDns.always = true
        assertEquals(real, resolver(Dns { sinkhole }).lookup("example.test-h"))
    }
}
