package dev.dialer.voice.data

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mints a Twilio Access Token (HS256 JWT) on the device.
 *
 * Claim layout was taken from `twilio.jwt.AccessToken.toJwt()` output, not from
 * documentation: `iss` is the API key SID, `sub` is the account SID, and `jti`
 * is "<apiKeySid>-<expiry>". Twilio rejects the token if iss/sub are swapped,
 * and the Voice SDK refuses to dial unless voice.outgoing.application_sid is set.
 */
object TwilioJwt {

    fun mint(
        accountSid: String,
        apiKeySid: String,
        apiKeySecret: String,
        twimlAppSid: String,
        identity: String,
        pushCredentialSid: String = "",
        ttlSeconds: Long = 3600,
    ): String {
        require(accountSid.isTwilioSid("AC")) { "Account SID must be a valid AC SID" }
        require(apiKeySid.isTwilioSid("SK")) { "API key SID must be a valid SK SID" }
        require(apiKeySecret.isNotBlank()) { "API key secret is blank" }
        require(twimlAppSid.isTwilioSid("AP")) { "TwiML App SID must be a valid AP SID" }
        require(identity.isNotBlank()) { "Identity is blank" }
        require(ttlSeconds > 0) { "Token lifetime must be positive" }
        require(pushCredentialSid.isBlank() || pushCredentialSid.isTwilioSid("CR")) {
            "Push Credential SID must be blank or a valid CR SID"
        }

        val issuedAt = System.currentTimeMillis() / 1000
        val expiresAt = issuedAt + ttlSeconds

        val header = JSONObject()
            .put("alg", "HS256")
            .put("cty", "twilio-fpa;v=1")
            .put("typ", "JWT")

        val voice = JSONObject()
            .put("incoming", JSONObject().put("allow", true))
            .put("outgoing", JSONObject().put("application_sid", twimlAppSid))
        if (pushCredentialSid.isNotBlank()) {
            voice.put("push_credential_sid", pushCredentialSid)
        }

        val payload = JSONObject()
            // Match Twilio's AccessToken helpers: jti is the key SID plus the
            // token's issued-at epoch seconds (not its expiry time).
            .put("jti", "$apiKeySid-$issuedAt")
            .put("grants", JSONObject().put("identity", identity).put("voice", voice))
            .put("iat", issuedAt)
            .put("exp", expiresAt)
            .put("iss", apiKeySid)
            .put("sub", accountSid)

        val signingInput = "${encode(header.toString())}.${encode(payload.toString())}"
        val signature = encode(hmacSha256(apiKeySecret.toByteArray(), signingInput.toByteArray()))
        return "$signingInput.$signature"
    }

    /** Returns the decoded claims, or null if the token is not a 3-part JWT. */
    fun claims(jwt: String): JSONObject? {
        val parts = jwt.split('.')
        if (parts.size != 3) return null
        return runCatching {
            JSONObject(parts[1].decodeBase64()?.utf8() ?: return null)
        }.getOrNull()
    }

    /** Verifies the HS256 signature and expiry — catches a wrong secret locally. */
    fun verify(jwt: String, apiKeySecret: String): Boolean {
        val parts = jwt.split('.')
        if (parts.size != 3) return false
        val expected = hmacSha256(apiKeySecret.toByteArray(), "${parts[0]}.${parts[1]}".toByteArray())
        val actual = parts[2].decodeBase64()?.toByteArray()
            ?: return false
        if (expected.size != actual.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[i].toInt())
        val claims = claims(jwt) ?: return false
        return diff == 0 && claims.optLong("exp") > System.currentTimeMillis() / 1000
    }

    private fun encode(bytes: ByteArray): String =
        bytes.toByteString().base64Url().trimEnd('=')

    private fun encode(text: String): String = encode(text.toByteArray())

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
