package com.mediplus.spapp.domain.model

/**
 * The member details the back office returns for a verified card. Shown on the confirmation step so
 * the operator can check them against the person in front of them (FR-011).
 */
data class MemberDetails(
    val memberNumber: String,
    val fullName: String,
    val dateOfBirth: String,
    val membershipStatus: String,
    val plan: String?,
)

/**
 * The authoritative server verdict for a scanned member card (FR-008). Membership validity is
 * entirely server-owned — a member card carries no expiry, so there is no local pre-check.
 */
data class MemberVerification(
    val status: Status,
    val reason: String?,
    val memberVerified: Boolean,
    val memberResolved: Boolean,
    val referenceOnFile: Boolean,
    val member: MemberDetails?,
) {
    enum class Status { VALID, INVALID }
}
