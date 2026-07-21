package com.mediplus.faceverify.domain.model

import java.time.LocalDate

/** On-device chip-integrity outcome (Decision 4). The server holds the authoritative verdict. */
enum class DocIntegrityResult { PASSED, FAILED, NOT_CHECKED }

/** The key that unlocks eMRTD secure messaging, derived from the MRZ or a CAN (Decision 3). */
sealed interface DocAccessKey {
    /** BAC/PACE key derived from MRZ fields (dates are YYMMDD as printed in the MRZ). */
    data class Mrz(
        val memberNumber: String,
        val dateOfBirthYyMmDd: String,
        val expiryYyMmDd: String,
    ) : DocAccessKey

    /** Card Access Number (some national eIDs). */
    data class Can(val can: String) : DocAccessKey
}

/** Identity fields parsed from DG1/MRZ. */
data class DocumentIdentity(
    val memberNumber: String,
    val surname: String,
    val givenNames: String,
    val dateOfBirth: String,
    val nationality: String,
    val sex: String,
    val expiryDate: LocalDate,
    val issuingAuthority: String,
)

/**
 * A document read on-device (FR-007, FR-011). [referencePhoto] (DG2) is transient and cleared with
 * the verification submission; [memberNumber] is the patient key sent to the back office (FR-011a).
 */
data class ReadDocument(
    val memberNumber: String,
    val identity: DocumentIdentity,
    val referencePhoto: ByteArray?,
    val securityObjectBase64: String?,
    val dataGroupHashes: Map<String, String>,
    val localIntegrity: DocIntegrityResult,
) {
    // ByteArray in a data class needs structural equality by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReadDocument) return false
        return memberNumber == other.memberNumber &&
            identity == other.identity &&
            referencePhoto.contentEqualsNullable(other.referencePhoto) &&
            securityObjectBase64 == other.securityObjectBase64 &&
            dataGroupHashes == other.dataGroupHashes &&
            localIntegrity == other.localIntegrity
    }

    override fun hashCode(): Int {
        var result = memberNumber.hashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + (referencePhoto?.contentHashCode() ?: 0)
        result = 31 * result + (securityObjectBase64?.hashCode() ?: 0)
        result = 31 * result + dataGroupHashes.hashCode()
        result = 31 * result + localIntegrity.hashCode()
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
    if (this == null) return other == null
    if (other == null) return false
    return this.contentEquals(other)
}

/** Authoritative server verdict for a read document (FR-008). */
data class DocumentValidation(
    val authenticity: Authenticity,
    val reason: String?,
    val memberVerified: Boolean,
    val referenceOnFile: Boolean,
    val patientResolved: Boolean,
) {
    enum class Authenticity { VALID, INVALID }
}
