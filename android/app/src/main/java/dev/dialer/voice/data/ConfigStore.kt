package dev.dialer.voice.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.dialer.voice.diagnostics.DiagnosticLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val TWILIO_SID = Regex("^[A-Z]{2}[0-9a-fA-F]{32}$")
private val AUSTRALIAN_E164 = Regex("^\\+61[1-9]\\d{5,9}$")

internal fun String.isTwilioSid(prefix: String): Boolean =
    startsWith(prefix) && matches(TWILIO_SID)

@Serializable
data class AppConfig(
    val accountSid: String = "",
    val apiKeySid: String = "",
    val apiKeySecret: String = "",
    val twimlAppSid: String = "",
    val phoneNumber: String = "",
    val pushCredentialSid: String = "",
    val identity: String = "default",
) {
    /** The app signs short-lived Voice SDK tokens locally; no backend token API is needed. */
    val canMintToken: Boolean
        get() = accountSid.isTwilioSid("AC") && apiKeySid.isTwilioSid("SK") &&
            apiKeySecret.isNotBlank() && twimlAppSid.isTwilioSid("AP") &&
            phoneNumber.matches(AUSTRALIAN_E164) && identity.isNotBlank()

    val hasPushCredential: Boolean get() = pushCredentialSid.isTwilioSid("CR")

    val isConfigured: Boolean get() = canMintToken
}

object ConfigStore {

    private lateinit var encryptedFile: File
    private lateinit var legacyFile: File
    private val json = Json { ignoreUnknownKeys = true }
    private const val KEY_ALIAS = "twilio-dialer-config-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    fun init(context: Context) {
        encryptedFile = File(context.filesDir, "config.enc")
        legacyFile = File(context.filesDir, "config.json")
    }

    fun load(): AppConfig {
        if (encryptedFile.exists()) {
            return runCatching {
                val payload = AtomicFile(encryptedFile).openRead().use { it.readBytes() }
                json.decodeFromString<AppConfig>(decrypt(payload))
            }.getOrElse { error ->
                DiagnosticLog.error(
                    "Settings storage",
                    "Could not decrypt saved settings: ${error.javaClass.simpleName}",
                )
                AppConfig()
            }
        }

        // Migrate older installs once, encrypting their existing private config before
        // removing the plaintext file. Unknown fields from the old Vercel config are ignored.
        if (legacyFile.exists()) {
            val legacy = runCatching {
                json.decodeFromString<AppConfig>(legacyFile.readText())
            }.getOrElse { error ->
                DiagnosticLog.error(
                    "Settings migration",
                    "Could not read legacy settings: ${error.javaClass.simpleName}",
                )
                return AppConfig()
            }
            runCatching { save(legacy) }
                .onSuccess { legacyFile.delete() }
                .onFailure { error ->
                    DiagnosticLog.error(
                        "Settings migration",
                        "Could not encrypt legacy settings: ${error.javaClass.simpleName}",
                    )
                }
            return legacy
        }
        return AppConfig()
    }

    fun save(config: AppConfig) {
        val payload = encrypt(json.encodeToString(config))
        val atomic = AtomicFile(encryptedFile)
        val output = atomic.startWrite()
        try {
            output.write(payload)
            atomic.finishWrite(output)
            if (legacyFile.exists()) legacyFile.delete()
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return cipher.iv + ciphertext
    }

    private fun decrypt(payload: ByteArray): String {
        require(payload.size > IV_LENGTH) { "Encrypted settings are incomplete" }
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
