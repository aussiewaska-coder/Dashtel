package dev.dialer.voice.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    private val valid = AppConfig(
        accountSid = "AC" + "0".repeat(32),
        apiKeySid = "SK" + "0".repeat(32),
        apiKeySecret = "secret",
        twimlAppSid = "AP" + "0".repeat(32),
        phoneNumber = "+61412345678",
        identity = "default",
    )

    @Test
    fun `fully valid outbound config is accepted`() {
        assertTrue(valid.canMintToken)
    }

    @Test
    fun `prefix-only placeholders are rejected`() {
        assertFalse(valid.copy(accountSid = "AC-not-a-real-sid").canMintToken)
        assertFalse(valid.copy(apiKeySid = "SK-not-a-real-sid").canMintToken)
        assertFalse(valid.copy(twimlAppSid = "AP-not-a-real-sid").canMintToken)
    }

    @Test
    fun `outbound identity must be an Australian number`() {
        assertFalse(valid.copy(phoneNumber = "+442079460018").canMintToken)
    }

    @Test
    fun `push credential is optional but must be a complete CR SID`() {
        assertFalse(valid.hasPushCredential)
        assertFalse(valid.copy(pushCredentialSid = "CR-short").hasPushCredential)
        assertTrue(
            valid.copy(pushCredentialSid = "CR" + "0".repeat(32))
                .hasPushCredential,
        )
    }
}
