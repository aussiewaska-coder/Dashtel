package dev.dialer.voice.twilio

import dev.dialer.voice.data.AppConfig
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object RegistrationPolicy {

    val refreshAfterMillis: Long = TimeUnit.DAYS.toMillis(180)

    /**
     * A non-reversible identifier for the Twilio/FCM binding. The FCM token is
     * intentionally never stored directly in app preferences or diagnostics.
     */
    fun fingerprint(config: AppConfig, senderId: String, fcmToken: String): String {
        val binding = listOf(
            config.accountSid.trim(),
            config.identity.trim(),
            config.pushCredentialSid.trim(),
            senderId.trim(),
            fcmToken.trim(),
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(binding.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun needsRegistration(
        previousFingerprint: String?,
        registeredAtMillis: Long,
        nowMillis: Long,
        currentFingerprint: String,
    ): Boolean {
        if (previousFingerprint != currentFingerprint) return true
        val age = nowMillis - registeredAtMillis
        return age < 0L || age >= refreshAfterMillis
    }
}
