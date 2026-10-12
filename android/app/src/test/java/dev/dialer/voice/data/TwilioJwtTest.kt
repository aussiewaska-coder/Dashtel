package dev.dialer.voice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwilioJwtTest {

    private val accountSid = "AC" + "0".repeat(32)
    private val apiKeySid = "SK" + "0".repeat(32)
    private val secret = "test-secret-that-never-leaves-the-test"
    private val appSid = "AP" + "0".repeat(32)

    @Test
    fun `mints Twilio voice claims and verifies signature`() {
        val jwt = TwilioJwt.mint(
            accountSid = accountSid,
            apiKeySid = apiKeySid,
            apiKeySecret = secret,
            twimlAppSid = appSid,
            identity = "default",
            pushCredentialSid = "CR" + "0".repeat(32),
        )

        val claims = TwilioJwt.claims(jwt)
        assertNotNull(claims)
        assertEquals(apiKeySid, claims!!.getString("iss"))
        assertEquals(accountSid, claims.getString("sub"))
        assertEquals("default", claims.getJSONObject("grants").getString("identity"))
        val voice = claims.getJSONObject("grants").getJSONObject("voice")
        assertEquals(appSid, voice.getJSONObject("outgoing").getString("application_sid"))
        assertEquals("CR" + "0".repeat(32), voice.getString("push_credential_sid"))
        assertTrue(TwilioJwt.verify(jwt, secret))
    }

    @Test
    fun `rejects a token checked with another secret`() {
        val jwt = TwilioJwt.mint(accountSid, apiKeySid, secret, appSid, "default")

        assertFalse(TwilioJwt.verify(jwt, "another-secret"))
    }
}
