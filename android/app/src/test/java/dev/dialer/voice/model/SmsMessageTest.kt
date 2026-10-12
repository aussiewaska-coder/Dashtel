package dev.dialer.voice.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmsMessageTest {
    @Test fun `SMS recipient accepts supported countries but rejects extensions and sender IDs`() {
        assertEquals("+61400000000", normalizeSmsRecipient("0400 000 000"))
        assertEquals("+85512345678", normalizeSmsRecipient("+855 12 345 678"))
        assertEquals("+66812345678", normalizeSmsRecipient("+66 81 234 5678"))
        listOf("BANK123456789", "0400000000,123", "0400000000#", "+12025550123", "", "sms:+61400000000").forEach {
            assertNull(it, normalizeSmsRecipient(it))
        }
    }

    @Test fun `segment estimates handle GSM extensions and UTF16 emoji`() {
        assertEquals(0, smsSegments(""))
        assertEquals(1, smsSegments("a".repeat(160)))
        assertEquals(2, smsSegments("a".repeat(161)))
        assertEquals(1, smsSegments("^".repeat(80)))
        assertEquals(2, smsSegments("^".repeat(81)))
        assertEquals(1, smsSegments("ก".repeat(70)))
        assertEquals(2, smsSegments("ก".repeat(71)))
        assertEquals(1, smsSegments("😃".repeat(35)))
        assertEquals(2, smsSegments("😃".repeat(36)))
    }

    @Test fun `queued and failed messages retain created time status and error`() {
        val message = SmsMessage.fromJson(JSONObject().apply {
            put("sid", "SM" + "0".repeat(32)); put("from", "+61400000000"); put("to", "+61400000001")
            put("body", "hello"); put("status", "failed"); put("date_sent", JSONObject.NULL)
            put("date_created", "Mon, 12 Oct 2026 00:00:00 +0000"); put("error_code", 21614); put("num_media", "0")
        })
        assertTrue(message.timestamp > 0)
        assertEquals("failed", message.status)
        assertEquals("21614", message.errorCode)
        assertEquals("+61400000001", message.peer("+61400000000"))
        assertFalse(message.incoming("+61400000000"))
    }
}
