package dev.dialer.voice.telecom

import org.junit.Assert.*
import org.junit.Test

class CallSessionGateTest {
    @Test fun pendingRequestDoesNotAllowAnotherCall() {
        val gate = CallSessionGate()
        assertTrue(gate.begin("a"))
        assertTrue(gate.isPending("a"))
        assertFalse(gate.begin("b"))
        assertEquals("a", gate.id)
    }

    @Test fun acknowledgmentEndsOnlyTheMatchingHandoffTimeout() {
        val gate = CallSessionGate()
        gate.begin("a")
        assertFalse(gate.attach("stale"))
        assertTrue(gate.isPending("a"))
        assertTrue(gate.attach("a"))
        assertFalse(gate.isPending("a"))
        assertFalse(gate.attach("a"))
    }

    @Test fun timedOutConnectionCannotAttachToTheNextCall() {
        val gate = CallSessionGate()
        gate.begin("a")
        assertTrue(gate.finish("a"))
        gate.begin("b")
        assertFalse(gate.attach("a"))
        assertTrue(gate.isPending("b"))
        assertTrue(gate.attach("b"))
    }

    @Test fun lateDisconnectCannotReleaseTheNextCallsAudioOwner() {
        val gate = CallSessionGate()
        gate.begin("a")
        gate.attach("a")
        gate.finish("a")
        gate.begin("b")
        gate.attach("b")
        assertFalse(gate.finish("a"))
        assertTrue(gate.owns("b"))
        assertTrue(gate.finish("b"))
        assertFalse(gate.finish("b"))
        assertFalse(gate.owns(null))
    }
}
