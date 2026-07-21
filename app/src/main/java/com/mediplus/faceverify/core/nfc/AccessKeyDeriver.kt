package com.mediplus.faceverify.core.nfc

import com.mediplus.faceverify.domain.model.DocAccessKey
import javax.inject.Inject

/**
 * Derives the eMRTD access key from the printed MRZ (via OCR text) or from operator entry
 * (Decision 3). MRZ parsing is pure and unit-testable — the ML Kit dependency lives in the caller,
 * which passes the recognized text here as a plain string.
 */
class AccessKeyDeriver @Inject constructor() {

    /**
     * Parses a recognized MRZ block into a BAC/PACE key. Supports TD3 (2×44, passports) and
     * TD1 (3×30, ID cards). Returns null when no complete, well-formed MRZ can be found.
     */
    fun deriveFromMrz(rawText: String): DocAccessKey.Mrz? {
        val lines = rawText
            .uppercase()
            .lineSequence()
            .map { it.replace(" ", "").trim() }
            .filter { it.matches(MRZ_LINE) }
            .toList()

        return parseTd3(lines) ?: parseTd1(lines)
    }

    /** Operator-entry fallback when OCR is unavailable or the MRZ can't be read. */
    fun fromManualEntry(memberNumber: String, dobYyMmDd: String, expiryYyMmDd: String): DocAccessKey.Mrz =
        DocAccessKey.Mrz(
            memberNumber = memberNumber.trim().uppercase().trimEnd('<'),
            dateOfBirthYyMmDd = dobYyMmDd.trim(),
            expiryYyMmDd = expiryYyMmDd.trim(),
        )

    private fun parseTd3(lines: List<String>): DocAccessKey.Mrz? {
        val line = lines.firstOrNull { it.length == TD3_LINE_LEN } ?: return null
        val memberNumber = line.substring(0, 9).trimEnd('<')
        val dob = line.substring(13, 19)
        val expiry = line.substring(21, 27)
        return buildKey(memberNumber, dob, expiry)
    }

    private fun parseTd1(lines: List<String>): DocAccessKey.Mrz? {
        // TD1 splits the key across the first line (document number) and second line (dates).
        val line1 = lines.firstOrNull { it.length == TD1_LINE_LEN } ?: return null
        val line2 = lines.filter { it.length == TD1_LINE_LEN }.getOrNull(1) ?: return null
        val memberNumber = line1.substring(5, 14).trimEnd('<')
        val dob = line2.substring(0, 6)
        val expiry = line2.substring(8, 14)
        return buildKey(memberNumber, dob, expiry)
    }

    private fun buildKey(memberNumber: String, dob: String, expiry: String): DocAccessKey.Mrz? {
        if (memberNumber.isBlank() || !dob.matches(SIX_DIGITS) || !expiry.matches(SIX_DIGITS)) return null
        return DocAccessKey.Mrz(memberNumber, dob, expiry)
    }

    private companion object {
        val MRZ_LINE = Regex("^[A-Z0-9<]{28,44}$")
        val SIX_DIGITS = Regex("^[0-9]{6}$")
        const val TD3_LINE_LEN = 44
        const val TD1_LINE_LEN = 30
    }
}
