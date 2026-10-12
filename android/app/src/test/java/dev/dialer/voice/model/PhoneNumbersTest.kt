package dev.dialer.voice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun `accepts Australian E164 with formatting whitespace`() {
        assertEquals("+61412345678", normalizeForDial("+61 412 345 678"))
    }

    @Test
    fun `normalizes Australian local formats`() {
        assertEquals("+61412345678", normalizeForDial("0412 345 678"))
        assertEquals("+61412345678", normalizeForDial("412 345 678"))
    }

    @Test
    fun `normalizes Australian international access prefix`() {
        assertEquals("+61412345678", normalizeForDial("00 61 412 345 678"))
    }

    @Test
    fun `accepts Australian service numbers`() {
        assertEquals("+611300123456", normalizeForDial("1300 123 456"))
        assertEquals("+61131234", normalizeForDial("13 12 34"))
    }

    @Test
    fun `accepts Cambodian and Thai international numbers`() {
        assertEquals("+85512345678", normalizeForDial("+855 12 345 678"))
        assertEquals("+66912345678", normalizeForDial("00 66 91 234 5678"))
    }

    @Test
    fun `creates post dial plan with pauses and extension digits`() {
        val plan = createDialPlan("0412 345 678,12#")

        assertEquals("+61412345678", plan?.destination)
        assertEquals(",12#", plan?.postDialDigits)
    }

    @Test
    fun `maps names to t9 digits`() {
        assertEquals("72724", t9Digits("Sarah"))
    }

    @Test
    fun `rejects invalid or ambiguous numbers`() {
        assertNull(normalizeForDial(""))
        assertNull(normalizeForDial("12345"))
        assertNull(normalizeForDial("++61412345678"))
        assertNull(normalizeForDial("+0123456789"))
        assertNull(normalizeForDial("+442079460018"))
        assertNull(normalizeForDial("00 44 20 7946 0018"))
    }
}
