package dev.dialer.voice.model

import com.twilio.voice.Call

enum class CallState {
    Idle,
    Connecting,
    Ringing,
    Incoming,
    Connected,
    Reconnecting,
    Disconnecting,
    Failed,
    ;

    companion object {
        fun from(call: Call?): CallState = when (call?.state) {
            null -> Idle
            Call.State.CONNECTING -> Connecting
            Call.State.RINGING -> Ringing
            Call.State.CONNECTED -> Connected
            Call.State.RECONNECTING -> Reconnecting
            Call.State.DISCONNECTED -> Failed
        }
    }
}
