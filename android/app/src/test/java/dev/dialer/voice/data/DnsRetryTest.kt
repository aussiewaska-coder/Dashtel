package dev.dialer.voice.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class DnsRetryTest {

    @Test
    fun `retries DNS failures and returns success`() = runBlocking {
        var attempts = 0
        val retryNumbers = mutableListOf<Int>()

        val result = retryUnknownHost(
            delayForAttempt = { 0L },
            onRetry = { attempt, _ -> retryNumbers += attempt },
        ) {
            attempts += 1
            if (attempts < 3) throw UnknownHostException("temporary DNS failure")
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(3, attempts)
        assertEquals(listOf(1, 2), retryNumbers)
    }

    @Test
    fun `does not retry ambiguous IO failures`() {
        var attempts = 0

        assertThrows(IOException::class.java) {
            runBlocking {
                retryUnknownHost(delayForAttempt = { 0L }) {
                    attempts += 1
                    throw IOException("response lost")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `stops after the configured DNS attempts`() {
        var attempts = 0

        assertThrows(UnknownHostException::class.java) {
            runBlocking {
                retryUnknownHost(maxAttempts = 3, delayForAttempt = { 0L }) {
                    attempts += 1
                    throw UnknownHostException("still unavailable")
                }
            }
        }
        assertEquals(3, attempts)
    }
}
