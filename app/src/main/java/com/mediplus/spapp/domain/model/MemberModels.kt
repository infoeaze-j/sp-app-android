package com.mediplus.spapp.domain.model

/**
 * The member details the back office returns for a verified card. Shown on the confirmation step so
 * the operator can check them against the person in front of them (FR-011).
 *
 * Membership *status* is deliberately absent: the back office reports a VALID/INVALID verdict and a
 * diagnostic reason it does not want rendered, so there is no status for the app to show.
 */
data class MemberDetails(
    val memberNumber: String,
    val fullName: String,
    val dateOfBirth: String?,
    val plan: String?,
)

/**
 * What the back office says this member may do next. Both are server-owned; the app enforces them
 * and never infers them.
 */
data class MemberCapabilities(
    /** False when there is no usable reference on file, so the face step cannot run. */
    val canVerifyFace: Boolean,
    /** False when the member may be identified but no service may be added for this visit. */
    val canEnroll: Boolean,
)

/**
 * The authoritative server verdict for a scanned member card (FR-008). Membership validity is
 * entirely server-owned — a member card carries no expiry, so there is no local pre-check. An
 * INVALID verdict arrives as a successful call, which is what lets the app tell a rejected card
 * apart from a network failure.
 *
 * [reason] is diagnostic only and is never rendered (FR-029).
 */
data class MemberVerification(
    val status: Status,
    val reason: String?,
    val referenceOnFile: Boolean,
    val member: MemberDetails?,
    val capabilities: MemberCapabilities = MemberCapabilities(canVerifyFace = false, canEnroll = false),
) {
    enum class Status { VALID, INVALID }
}
