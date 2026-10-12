package dev.dialer.voice.twilio

import dev.dialer.voice.data.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationPolicyTest {

    private val config = AppConfig(
        accountSid = "AC" + "0".repeat(32),
        apiKeySid = "SK" + "0".repeat(32),
        apiKeySecret = "secret",
        twimlAppSid = "AP" + "0".repeat(32),
        phoneNumber = "+61400000000",
        pushCredentialSid = "CR" + "0".repeat(32),
        identity = "default",
    )

    @Test
    fun `current binding does not register again`() {
        val now = 2_000_000_000_000L
        val fingerprint = RegistrationPolicy.fingerprint(config, "sender", "fcm-token")

        assertEquals(64, fingerprint.length)
        assertFalse(
            RegistrationPolicy.needsRegistration(
                previousFingerprint = fingerprint,
                registeredAtMillis = now - RegistrationPolicy.refreshAfterMillis + 1,
                nowMillis = now,
                currentFingerprint = fingerprint,
            ),
        )
    }

    @Test
    fun `changed FCM token requires registration`() {
        val oldFingerprint = RegistrationPolicy.fingerprint(config, "sender", "old-token")
        val newFingerprint = RegistrationPolicy.fingerprint(config, "sender", "new-token")

        assertNotEquals(oldFingerprint, newFingerprint)
        assertTrue(
            RegistrationPolicy.needsRegistration(
                previousFingerprint = oldFingerprint,
                registeredAtMillis = 1_000L,
                nowMillis = 2_000L,
                currentFingerprint = newFingerprint,
            ),
        )
    }

    @Test
    fun `old or future-dated registration is refreshed`() {
        val now = 2_000_000_000_000L
        val fingerprint = RegistrationPolicy.fingerprint(config, "sender", "fcm-token")

        assertTrue(
            RegistrationPolicy.needsRegistration(
                previousFingerprint = fingerprint,
                registeredAtMillis = now - RegistrationPolicy.refreshAfterMillis,
                nowMillis = now,
                currentFingerprint = fingerprint,
            ),
        )
        assertTrue(
            RegistrationPolicy.needsRegistration(
                previousFingerprint = fingerprint,
                registeredAtMillis = now + 1,
                nowMillis = now,
                currentFingerprint = fingerprint,
            ),
        )
    }
}
