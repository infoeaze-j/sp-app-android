package com.mediplus.spapp.core.nfc

import com.mediplus.spapp.domain.model.MemberNumber
import java.math.BigInteger

/**
 * Turns a tapped card's NFC tag UID into a member number (FR-007, FR-011a).
 *
 * The member card stock is MIFARE Classic 1K carrying no NDEF message: its first sectors are
 * factory-blank and its remaining sectors are locked with proprietary keys, so the UID is the only
 * identifier the card exposes to a stock Android reader. The number is the UID read as an unsigned
 * big-endian integer, in the byte order `android.nfc.Tag.getId()` reports.
 *
 * [MemberNumber.parse] still has the final say, so a UID that cannot produce a contract-shaped
 * number (`^[0-9]{7,32}$`) is rejected here rather than sent to the back office.
 */
object CardUid {

    /** The number this UID encodes, or null when it cannot form a well-formed member number. */
    fun toMemberNumber(uid: ByteArray): MemberNumber? {
        if (uid.isEmpty()) return null
        // Signum 1 with a big-endian magnitude: bytes stay unsigned and a 10-byte UID cannot
        // overflow the way a Long would.
        return MemberNumber.parse(BigInteger(1, uid).toString())
    }
}
