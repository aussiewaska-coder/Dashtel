package dev.dialer.voice.model

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

data class SmsMessage(
    val sid: String,
    val from: String,
    val to: String,
    val body: String,
    val status: String,
    val timestamp: Long,
    val errorCode: String? = null,
    val mediaCount: Int = 0,
    val preview: Boolean = false,
) {
    fun incoming(line: String) = to == line
    fun peer(line: String) = if (incoming(line)) from else to

    companion object {
        fun fromJson(json: JSONObject): SmsMessage {
            val date = listOf("date_sent", "date_created").firstNotNullOfOrNull { key ->
                runCatching {
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(json.optString(key))?.time
                }.getOrNull()
            } ?: 0L
            return SmsMessage(
                sid = json.getString("sid"), from = json.getString("from"), to = json.getString("to"),
                body = json.optString("body").takeUnless { it == "null" }.orEmpty(),
                status = json.optString("status"), timestamp = date,
                errorCode = json.optString("error_code").takeUnless { it.isBlank() || it == "null" },
                mediaCount = json.optInt("num_media", 0),
            )
        }
    }
}

data class SmsPage(val messages: List<SmsMessage>, val next: String?)

fun normalizeSmsRecipient(input: String): String? {
    if (!Regex("^[+0-9\\s().-]+$").matches(input)) return null
    return normalizeForDial(input)
}

/** GSM extension characters occupy two septets; non-GSM text uses UTF-16 units. */
fun smsSegments(body: String): Int {
    if (body.isEmpty()) return 0
    val basic = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    val extended = "\u000c^{}\\[~]|€"
    val gsm = body.all { it in basic || it in extended }
    val units = if (gsm) body.sumOf { if (it in extended) 2L else 1L }.toInt() else body.length
    val single = if (gsm) 160 else 70
    val multi = if (gsm) 153 else 67
    return if (units <= single) 1 else (units + multi - 1) / multi
}
