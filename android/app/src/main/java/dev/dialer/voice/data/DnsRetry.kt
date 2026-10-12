package dev.dialer.voice.data

import kotlinx.coroutines.delay
import java.net.UnknownHostException

/** Retries only DNS failures, which occur before an HTTP request reaches Twilio. */
internal suspend fun <T> retryUnknownHost(
    maxAttempts: Int = 3,
    delayForAttempt: (Int) -> Long = { attempt -> attempt * 1_000L },
    onRetry: (Int, UnknownHostException) -> Unit = { _, _ -> },
    block: () -> T,
): T {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    var lastError: UnknownHostException? = null
    repeat(maxAttempts) { index ->
        try {
            return block()
        } catch (error: UnknownHostException) {
            lastError = error
            val attempt = index + 1
            if (attempt < maxAttempts) {
                onRetry(attempt, error)
                delay(delayForAttempt(attempt).coerceAtLeast(0L))
            }
        }
    }
    throw requireNotNull(lastError)
}
