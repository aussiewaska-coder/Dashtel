package dev.dialer.voice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryTest {

    @Test
    fun `outbound parent and child become one logical call`() {
        val child = leg(
            sid = "CA-child",
            parent = "CA-parent",
            direction = "outbound-dial",
            from = "+61200000000",
            to = "+61411111111",
            duration = 42,
        )
        val parent = leg(
            sid = "CA-parent",
            direction = "inbound",
            from = "client:default",
            to = "+61411111111",
            duration = 45,
        )

        val calls = CallHistory.group(
            legs = listOf(child, parent),
            recordingsByCall = mapOf("CA-child" to listOf("RE-recording")),
        )

        assertEquals(1, calls.size)
        with(calls.single()) {
            assertEquals("CA-parent", sid)
            assertFalse(isInbound)
            assertEquals("+61411111111", to)
            assertEquals(45L, durationSeconds)
            assertEquals(listOf("CA-child", "CA-parent"), legSids)
            assertEquals(listOf("RE-recording"), recordingSids)
        }
    }

    @Test
    fun `inbound client child keeps the external caller`() {
        val parent = leg(
            sid = "CA-parent",
            direction = "inbound",
            from = "+61422222222",
            to = "+61200000000",
        )
        val child = leg(
            sid = "CA-child",
            parent = "CA-parent",
            direction = "outbound-dial",
            from = "+61200000000",
            to = "client:default",
        )

        val call = CallHistory.group(listOf(parent, child)).single()

        assertTrue(call.isInbound)
        assertEquals("+61422222222", call.from)
        assertEquals("CA-parent", call.sid)
    }

    @Test
    fun `orphaned paginated child still uses its parent as the stable id`() {
        val child = leg(
            sid = "CA-child",
            parent = "CA-parent-not-on-page",
            direction = "outbound-dial",
            from = "+61200000000",
            to = "+61433333333",
        )

        val call = CallHistory.group(listOf(child)).single()

        assertEquals("CA-parent-not-on-page", call.sid)
        assertEquals("+61433333333", call.to)
    }

    @Test
    fun `orphaned client child is recognized as inbound`() {
        val child = leg(
            sid = "CA-child",
            parent = "CA-parent-not-on-page",
            direction = "outbound-dial",
            from = "+61200000000",
            to = "client:default",
        )

        val call = CallHistory.group(listOf(child)).single()

        assertTrue(call.isInbound)
    }

    private fun leg(
        sid: String,
        parent: String? = null,
        direction: String,
        from: String,
        to: String,
        duration: Long = 30,
    ) = CallLeg(
        sid = sid,
        parentCallSid = parent,
        direction = direction,
        from = from,
        to = to,
        status = "completed",
        startedAt = "2026-10-10T06:00:00Z",
        endedAt = "2026-10-10T06:00:30Z",
        durationSeconds = duration,
    )
}
