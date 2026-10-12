package dev.dialer.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientDnsTest {

    private val systemAddress = InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))
    private val networkAddress = InetAddress.getByAddress(byteArrayOf(5, 6, 7, 8))
    private val secureAddress = InetAddress.getByAddress(byteArrayOf(9, 10, 11, 12))

    @Test
    fun `system result is preferred without fallbacks`() {
        var fallbackCalls = 0
        val dns = ResilientDns(
            systemLookup = { listOf(systemAddress) },
            activeNetworkLookup = { fallbackCalls += 1; listOf(networkAddress) },
            secureLookup = { fallbackCalls += 1; listOf(secureAddress) },
        )

        assertEquals(listOf(systemAddress), dns.lookup("api.twilio.com"))
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `active network recovers failed system DNS`() {
        val routes = mutableListOf<DnsRoute>()
        val dns = ResilientDns(
            systemLookup = { throw UnknownHostException() },
            activeNetworkLookup = { listOf(networkAddress) },
            secureLookup = { listOf(secureAddress) },
            onFallback = { route, _ -> routes += route },
        )

        assertEquals(listOf(networkAddress), dns.lookup("api.twilio.com"))
        assertEquals(listOf(DnsRoute.ActiveNetwork), routes)
    }

    @Test
    fun `secure DNS recovers both Android resolver failures`() {
        val routes = mutableListOf<DnsRoute>()
        val dns = ResilientDns(
            systemLookup = { throw UnknownHostException() },
            activeNetworkLookup = { emptyList() },
            secureLookup = { listOf(secureAddress) },
            onFallback = { route, _ -> routes += route },
        )

        assertEquals(listOf(secureAddress), dns.lookup("api.twilio.com"))
        assertEquals(listOf(DnsRoute.SecureFallback), routes)
    }

    @Test
    fun `unresolved hostname still throws UnknownHostException`() {
        val dns = ResilientDns(
            systemLookup = { emptyList() },
            activeNetworkLookup = { throw UnknownHostException() },
            secureLookup = { emptyList() },
        )

        assertThrows(UnknownHostException::class.java) {
            dns.lookup("api.twilio.com")
        }
    }
}
