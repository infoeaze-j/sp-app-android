package com.mediplus.spapp.core.nfc

import com.mediplus.spapp.domain.model.MemberNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The member card stock is MIFARE Classic 1K with no NDEF message and proprietary keys on its
 * data sectors, so the number is taken from the tag UID (FR-007, FR-010, FR-011a).
 *
 * Expected values are the unsigned big-endian decimal of the UID bytes, in the order
 * `android.nfc.Tag.getId()` reports them.
 */
class CardUidTest {

    private fun uid(hex: String): ByteArray =
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `decodes a real member card uid to its big-endian decimal`() {
        assertEquals("634743753", CardUid.toMemberNumber(uid("25 D5 6B C9"))?.value)
    }

    @Test
    fun `treats uid bytes as unsigned so a high bit is not a negative number`() {
        assertEquals("4294967295", CardUid.toMemberNumber(uid("FF FF FF FF"))?.value)
    }

    @Test
    fun `a seven byte uid does not overflow`() {
        assertEquals(
            "72057594037927935",
            CardUid.toMemberNumber(uid("FF FF FF FF FF FF FF"))?.value,
        )
    }

    @Test
    fun `a ten byte uid does not overflow a long`() {
        assertEquals(
            "1208925819614629174706175",
            CardUid.toMemberNumber(uid("FF FF FF FF FF FF FF FF FF FF"))?.value,
        )
    }

    @Test
    fun `a uid whose decimal is shorter than the server minimum is rejected`() {
        assertNull(CardUid.toMemberNumber(uid("00 00 00 01")))
    }

    @Test
    fun `an all-zero uid is rejected rather than becoming member zero`() {
        assertNull(CardUid.toMemberNumber(uid("00 00 00 00")))
    }

    @Test
    fun `an absent uid is rejected`() {
        assertNull(CardUid.toMemberNumber(ByteArray(0)))
    }

    @Test
    fun `every decoded number satisfies the back office contract`() {
        val contract = Regex("^[0-9]{7,32}\$")
        listOf("25 D5 6B C9", "FF FF FF FF", "FF FF FF FF FF FF FF", "04 A2 B3 C4 D5 E6 F7")
            .mapNotNull { CardUid.toMemberNumber(uid(it)) }
            .forEach { number ->
                assertEquals(true, contract.matches(number.value))
            }
    }
}
