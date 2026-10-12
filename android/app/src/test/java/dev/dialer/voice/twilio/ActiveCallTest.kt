package dev.dialer.voice.twilio

import dev.dialer.voice.model.CallState
import org.junit.Assert.*
import org.junit.Test

class ActiveCallTest {
    @Test fun cancellationForAnotherInviteDoesNotMatchCurrentCall() {
        val call = ActiveCall(state = CallState.Incoming, sid = "new-call")
        assertFalse(call.matchesIncoming("old-call"))
        assertTrue(call.matchesIncoming("new-call"))
    }

    @Test fun cancellationAfterAnswerCannotClearAnAcceptedCall() {
        for (state in listOf(CallState.Connecting, CallState.Connected, CallState.Reconnecting, CallState.Disconnecting)) {
            assertFalse(ActiveCall(state = state, sid = "call").matchesIncoming("call"))
        }
    }

    @Test fun disconnectingStillBlocksNewCallsAndKeepsTheServiceAlive() {
        assertTrue(ActiveCall(state = CallState.Disconnecting).isLive)
        assertFalse(ActiveCall(state = CallState.Idle).isLive)
        assertFalse(ActiveCall(state = CallState.Failed).isLive)
    }
}
