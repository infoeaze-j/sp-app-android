package com.mediplus.spapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The member card number format rule lives here and nowhere else: digits only, longer than
 * 6 characters, bounded above so a garbage NDEF payload cannot become an unbounded URL segment.
 */
class MemberNumberTest {

    @Test
    fun `six digits is too short`() {
        assertNull(MemberNumber.parse("123456"))
    }

    @Test
    fun `seven digits is the shortest accepted number`() {
        assertEquals("1234567", MemberNumber.parse("1234567")?.value)
    }

    @Test
    fun `thirty-two digits is accepted`() {
        val raw = "1".repeat(32)
        assertEquals(raw, MemberNumber.parse(raw)?.value)
    }

    @Test
    fun `thirty-three digits is rejected`() {
        assertNull(MemberNumber.parse("1".repeat(33)))
    }

    @Test
    fun `leading zeros are preserved, not normalised away`() {
        assertEquals("0001234", MemberNumber.parse("0001234")?.value)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("1234567", MemberNumber.parse("  1234567 \n")?.value)
    }

    @Test
    fun `letters are rejected`() {
        assertNull(MemberNumber.parse("P1234567"))
    }

    @Test
    fun `interior spaces are rejected`() {
        assertNull(MemberNumber.parse("123 4567"))
    }

    @Test
    fun `a leading plus is rejected`() {
        assertNull(MemberNumber.parse("+1234567"))
    }

    @Test
    fun `empty and null are rejected`() {
        assertNull(MemberNumber.parse(""))
        assertNull(MemberNumber.parse("   "))
        assertNull(MemberNumber.parse(null))
    }

    @Test
    fun `toString never exposes the digits`() {
        val number = MemberNumber.parse("1234567")!!

        assertEquals("MemberNumber(***)", number.toString())
        assertEquals("MemberNumber(***)", "$number")
        assertEquals("1234567", number.value)
    }
}
