package dev.dialer.voice.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CallRecord(
    @SerialName("sid") val sid: String,
    @SerialName("direction") val direction: String,
    @SerialName("from") val from: String,
    @SerialName("to") val to: String,
    @SerialName("status") val status: String,
    @SerialName("startedAt") val startedAt: String? = null,
    @SerialName("endedAt") val endedAt: String? = null,
    @SerialName("durationSeconds") val durationSeconds: Long? = null,
    @SerialName("recordingSids") val recordingSids: List<String> = emptyList(),
    /** All Twilio call legs represented by this user-visible call. */
    @SerialName("legSids") val legSids: List<String> = listOf(sid),
) {
    val isInbound: Boolean get() = direction.startsWith("inbound")
    val durationLabel: String
        get() {
            val seconds = durationSeconds ?: return "-"
            return "%02d:%02d".format(seconds / 60, seconds % 60)
        }
}

@Serializable
data class Recording(
    @SerialName("sid") val sid: String,
    @SerialName("durationSeconds") val durationSeconds: Long? = null,
    @SerialName("channels") val channels: String? = null,
    @SerialName("status") val status: String? = null,
)
