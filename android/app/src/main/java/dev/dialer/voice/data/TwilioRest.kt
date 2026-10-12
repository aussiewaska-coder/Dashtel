package dev.dialer.voice.data

import android.content.Context
import dev.dialer.voice.model.CallRecord
import dev.dialer.voice.model.CallHistory
import dev.dialer.voice.model.CallLeg
import dev.dialer.voice.model.Recording
import dev.dialer.voice.diagnostics.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Talks to the Twilio REST API straight from the phone, for the case where the
 * keys live in Settings and the server holds no credentials.
 *
 * Twilio's JSON is snake_case, so fields are read explicitly rather than through
 * the camelCase SerialNames used by the Android UI models.
 */
object TwilioRest {

    private const val BASE = "https://api.twilio.com/2010-04-01/Accounts"

    private lateinit var http: OkHttpClient

    fun init(context: Context) {
        if (::http.isInitialized) return
        synchronized(this) {
            if (::http.isInitialized) return
            http = OkHttpClient.Builder()
                .dns(
                    ResilientDns.create(context.applicationContext) { route, hostname ->
                        val message = when (route) {
                            DnsRoute.ActiveNetwork ->
                                "Android system DNS failed; active-network DNS resolved $hostname"
                            DnsRoute.SecureFallback ->
                                "Android DNS failed; secure DNS fallback resolved $hostname"
                        }
                        DiagnosticLog.warn("Twilio network", message)
                    },
                )
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
        }
    }

    open class TwilioApiError(
        val httpStatus: Int,
        val twilioCode: String?,
        message: String,
    ) : IOException(
        buildString {
            append("HTTP ").append(httpStatus)
            if (!twilioCode.isNullOrBlank()) append(" · Twilio code ").append(twilioCode)
            append(": ").append(message)
        }
    )

    class BadCredentials(httpStatus: Int, twilioCode: String?, message: String) :
        TwilioApiError(httpStatus, twilioCode, message)

    /** suffix is appended to …/Accounts/{sid} with no separator added. */
    private fun endpoint(config: AppConfig, suffix: String) =
        "$BASE/${config.accountSid.trim()}$suffix"

    private fun authHeader(config: AppConfig) =
        Credentials.basic(config.apiKeySid.trim(), config.apiKeySecret)

    // A JSON null surfaces through Android's optString as the literal "null".
    private fun JSONObject.text(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.positiveLong(key: String): Long? =
        optLong(key, -1L).takeIf { it >= 0 }

    private suspend fun get(config: AppConfig, suffix: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(config, suffix))
            .header("Authorization", authHeader(config))
            .get()
            .build()
        retryTwilioDns {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val errorJson = runCatching { JSONObject(body) }.getOrNull()
                val twilioCode = errorJson?.optString("code")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                val twilioMessage = errorJson?.optString("message")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                when {
                    response.code == 401 -> throw BadCredentials(
                        response.code,
                        twilioCode,
                        twilioMessage ?: "HTTP 401 Unauthorized. Check the Account SID and matching API key credentials.",
                    )
                    !response.isSuccessful -> throw TwilioApiError(
                        response.code,
                        twilioCode,
                        twilioMessage ?: "Twilio returned an error response.",
                    )
                    else -> JSONObject(body)
                }
            }
        }
    }

    private suspend fun <T> retryTwilioDns(block: () -> T): T = retryUnknownHost(
        onRetry = { attempt, _ ->
            DiagnosticLog.warn(
                "Twilio network",
                "DNS lookup failed; retrying request ($attempt/3)",
            )
        },
        block = block,
    )

    /** Checks credentials, TwiML App SID, and caller ID number against this Twilio account. */
    suspend fun verify(config: AppConfig): String {
        val application = get(config, "/Applications/${config.twimlAppSid.trim()}.json")
        val voiceUrl = application.text("voice_url")
        if (voiceUrl.isNullOrBlank()) {
            throw IOException("TwiML App ${config.twimlAppSid.trim()} has no Voice URL")
        }

        val encodedNumber = URLEncoder.encode(config.phoneNumber.trim(), StandardCharsets.UTF_8.name())
        val numbers = get(config, "/IncomingPhoneNumbers.json?PhoneNumber=$encodedNumber")
            .optJSONArray("incoming_phone_numbers")
        val numberExists = numbers?.let { array ->
            (0 until array.length()).any { index ->
                array.optJSONObject(index)?.text("phone_number") == config.phoneNumber.trim()
            }
        } == true
        if (!numberExists) {
            throw IOException("Twilio number ${config.phoneNumber.trim()} is not assigned to this account")
        }

        val appName = application.text("friendly_name") ?: config.twimlAppSid.trim()
        return "TwiML App $appName · caller ID verified"
    }

    /**
     * Creates a real incoming Voice SDK call to this installation's Twilio identity.
     * The short delay belongs in the caller so the user can lock the handset first.
     */
    suspend fun createIncomingTestCall(config: AppConfig): String = withContext(Dispatchers.IO) {
        require(config.canMintToken) { "Complete all Twilio fields first" }
        require(config.hasPushCredential) { "Add the Push Credential SID before testing incoming calls" }

        val twiml = "<Response><Say voice=\"Polly.Nicole\">" +
            "Hello. This is your DashPhone locked screen test call.</Say><Pause length=\"2\"/></Response>"
        val form = FormBody.Builder()
            .add("To", "client:${config.identity.trim()}")
            .add("From", config.phoneNumber.trim())
            .add("Twiml", twiml)
            .build()
        val request = Request.Builder()
            .url(endpoint(config, "/Calls.json"))
            .header("Authorization", authHeader(config))
            .post(form)
            .build()

        retryTwilioDns {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val twilioCode = json?.text("code")
                val message = json?.text("message")
                if (!response.isSuccessful) {
                    throw TwilioApiError(
                        response.code,
                        twilioCode,
                        message ?: "Twilio could not create the test call.",
                    )
                }
                json?.text("sid") ?: throw IOException("Twilio created no Call SID")
            }
        }
    }

    suspend fun listCalls(config: AppConfig): List<CallRecord> = withContext(Dispatchers.IO) {
        val array = get(config, "/Calls.json?PageSize=50").optJSONArray("calls")
            ?: return@withContext emptyList()
        // A Call resource carries no recording sid, so index one flat list.
        val recordingsByCall = runCatching { recordingIndex(config) }.getOrDefault(emptyMap())
        val legs = buildList {
            for (i in 0 until array.length()) {
                val call = array.optJSONObject(i) ?: continue
                val sid = call.text("sid") ?: continue
                add(
                    CallLeg(
                        sid = sid,
                        parentCallSid = call.text("parent_call_sid"),
                        direction = call.text("direction") ?: "",
                        from = call.text("from") ?: "",
                        to = call.text("to") ?: "",
                        status = call.text("status") ?: "",
                        startedAt = call.text("start_time"),
                        endedAt = call.text("end_time"),
                        durationSeconds = call.positiveLong("duration"),
                    )
                )
            }
        }
        CallHistory.group(legs, recordingsByCall)
    }

    private suspend fun recordingIndex(config: AppConfig): Map<String, List<String>> {
        val array = get(config, "/Recordings.json?PageSize=100").optJSONArray("recordings")
            ?: return emptyMap()
        val index = HashMap<String, MutableList<String>>()
        for (i in 0 until array.length()) {
            val recording = array.optJSONObject(i) ?: continue
            val callSid = recording.text("call_sid") ?: continue
            val sid = recording.text("sid") ?: continue
            index.getOrPut(callSid) { mutableListOf() }.add(sid)
        }
        return index
    }

    suspend fun listRecordings(config: AppConfig, callSid: String): List<Recording> =
        withContext(Dispatchers.IO) {
            val array = get(config, "/Recordings.json?CallSid=$callSid&PageSize=20")
                .optJSONArray("recordings") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val recording = array.optJSONObject(i) ?: continue
                    val sid = recording.text("sid") ?: continue
                    add(
                        Recording(
                            sid = sid,
                            durationSeconds = recording.positiveLong("duration"),
                            channels = recording.text("channels"),
                            status = recording.text("status"),
                        )
                    )
                }
            }
        }

    /**
     * Twilio's media URL requires Basic auth, which an external player cannot
     * attach, so the bytes are fetched here for a caller to hand to a player.
     */
    suspend fun downloadRecording(config: AppConfig, recordingSid: String, target: File): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint(config, "/Recordings/$recordingSid.mp3"))
                .header("Authorization", authHeader(config))
                .get()
                .build()
            retryTwilioDns {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Recording download HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Recording download returned an empty body")
                    try {
                        target.outputStream().use { output ->
                            body.byteStream().use { input -> input.copyTo(output) }
                        }
                        target
                    } catch (error: Exception) {
                        target.delete()
                        throw error
                    }
                }
            }
        }
}
