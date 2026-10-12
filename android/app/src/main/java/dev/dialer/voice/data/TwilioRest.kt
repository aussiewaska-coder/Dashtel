package dev.dialer.voice.data

import android.content.Context
import dev.dialer.voice.model.CallRecord
import dev.dialer.voice.model.CallHistory
import dev.dialer.voice.model.CallLeg
import dev.dialer.voice.model.Recording
import dev.dialer.voice.model.SmsMessage
import dev.dialer.voice.model.SmsPage
import dev.dialer.voice.model.normalizeSmsRecipient
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    suspend fun listMessages(config: AppConfig, incoming: Boolean, next: String? = null): SmsPage {
        val prefix = "/2010-04-01/Accounts/${config.accountSid.trim()}"
        val suffix = if (next == null) {
            val filter = if (incoming) "To" else "From"
            "/Messages.json?PageSize=50&$filter=${URLEncoder.encode(config.phoneNumber.trim(), "UTF-8") }"
        } else {
            // Never attach account credentials to a server-supplied foreign URL.
            require(next.startsWith("$prefix/Messages.json?")) { "Invalid message page" }
            next.removePrefix(prefix)
        }
        val json = get(config, suffix)
        val array = json.optJSONArray("messages")
        val messages = buildList {
            if (array != null) for (i in 0 until array.length()) {
                val message = SmsMessage.fromJson(array.getJSONObject(i))
                if (message.from == config.phoneNumber.trim() || message.to == config.phoneNumber.trim()) add(message)
            }
        }
        return SmsPage(messages, json.text("next_page_uri"))
    }

    suspend fun getMessage(config: AppConfig, sid: String): SmsMessage {
        require(Regex("^[SM]M[0-9a-fA-F]{32}$").matches(sid))
        return SmsMessage.fromJson(get(config, "/Messages/$sid.json"))
    }

    suspend fun sendMessage(config: AppConfig, to: String, body: String): SmsMessage {
        require(normalizeSmsRecipient(to) == to) { "Use an Australian, Cambodian or Thai number" }
        require(body.isNotBlank() && body.length <= 1600) { "Enter a message of up to 1,600 characters" }
        return SmsMessage.fromJson(postSms(config, "/Messages.json", FormBody.Builder()
            .add("From", config.phoneNumber.trim()).add("To", to).add("Body", body).build()))
    }

    private suspend fun postSms(config: AppConfig, suffix: String, form: FormBody): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint(config, suffix))
                .header("Authorization", authHeader(config)).post(form).build()
            // A lost response may still have sent the SMS. Never automatically replay a POST.
            http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build()
                .newCall(request).execute().use { response ->
                    val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    if (!response.isSuccessful) throw TwilioApiError(response.code, json?.text("code"),
                        json?.text("message") ?: "Twilio rejected the request")
                    json ?: throw IOException("Twilio returned an unreadable response")
                }
        }

    suspend fun smsLine(config: AppConfig): JSONObject {
        val number = URLEncoder.encode(config.phoneNumber.trim(), "UTF-8")
        val numbers = get(config, "/IncomingPhoneNumbers.json?PhoneNumber=$number").optJSONArray("incoming_phone_numbers")
        val line = (0 until (numbers?.length() ?: 0)).map { numbers!!.getJSONObject(it) }
            .firstOrNull { it.optString("phone_number") == config.phoneNumber.trim() }
            ?: throw IOException("The configured number is not assigned to this account")
        require(line.optJSONObject("capabilities")?.optBoolean("sms") == true) {
            "This Twilio number does not support SMS. Choose an SMS-capable number in Settings."
        }
        return line
    }

    suspend fun enableSmsPush(config: AppConfig, deviceToken: String) {
        val line = smsLine(config)
        val voiceUrl = get(config, "/Applications/${config.twimlAppSid.trim()}.json").text("voice_url")
            ?.toHttpUrl() ?: throw IOException("Configure the Twilio Voice webhook first")
        require(voiceUrl.isHttps && voiceUrl.host.endsWith(".twil.io")) { "SMS setup needs the deployed Twilio Function URL" }
        val target = voiceUrl.newBuilder().encodedPath("/sms").query(null).fragment(null).build()
        val ready = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(target.newBuilder().encodedPath("/sms-status").build()).get().build()
            http.newBuilder().followRedirects(false).build().newCall(request).execute().use { response ->
                response.isSuccessful && runCatching { JSONObject(response.body?.string().orEmpty()).optBoolean("ready") }.getOrDefault(false)
            }
        }
        require(ready) { "Deploy the SMS relay and its private Firebase credential before enabling notifications." }
        val existing = line.text("sms_url")
        val existingUrl = existing?.let { runCatching { it.toHttpUrl() }.getOrNull() }
        val ours = existingUrl?.let { it.host == target.host && it.encodedPath == "/sms" && it.isHttps } == true
        require(line.text("sms_application_sid").isNullOrBlank()) { "This number uses a Messaging TwiML app. Remove that SMS routing in Twilio before enabling notifications." }
        require(existing.isNullOrBlank() || ours || existingUrl?.host == "demo.twilio.com") {
            "This number already has another SMS webhook. Clear it in Twilio before enabling DashPhone notifications."
        }
        require(Regex("^[a-zA-Z0-9_:-]{32,512}$").matches(deviceToken)) { "Invalid Firebase device token" }
        if (!ours || existingUrl?.queryParameter("device") != deviceToken) {
            val sid = line.getString("sid")
            require(Regex("^PN[a-fA-F0-9]{32}$").matches(sid))
            postSms(config, "/IncomingPhoneNumbers/$sid.json", FormBody.Builder()
                .add("SmsUrl", target.newBuilder().addQueryParameter("device", deviceToken).build().toString())
                .add("SmsMethod", "POST").build())
        }
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
