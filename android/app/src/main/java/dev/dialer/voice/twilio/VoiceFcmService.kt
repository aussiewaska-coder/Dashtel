package dev.dialer.voice.twilio

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.dialer.voice.diagnostics.DiagnosticLog

class VoiceFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        VoiceManager.onNewFcmToken()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data
        if (payload.isEmpty()) return
        // Twilio Voice FCM messages identify themselves with `twi_message_type`
        // (e.g. `twilio.voice.call` / `twilio.voice.cancel`). Do not log payload
        // values: they contain call metadata and may include personal data.
        val messageType = payload["twi_message_type"]
        if (messageType == "twilio.voice.call" || messageType == "twilio.voice.cancel") {
            DiagnosticLog.info("Incoming push", "Twilio FCM message received: $messageType")
            VoiceManager.handleVoiceMessage(applicationContext, payload)
        } else {
            DiagnosticLog.warn(
                "Incoming push",
                "Ignored FCM data message; Twilio message type missing or unrecognized",
            )
        }
    }
}
