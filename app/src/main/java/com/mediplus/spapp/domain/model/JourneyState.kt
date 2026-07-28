package com.mediplus.spapp.domain.model

import kotlin.time.Duration

/**
 * The composite precondition for enrollment (FR-024, FR-025, FR-026). Enrollment is permitted only
 * when every part is satisfied *and* the verification is still fresh.
 *
 * @param memberNumber ties the composite to exactly one patient (FR-011a)
 * @param memberVerified set only on server VALID + memberVerified (FR-008)
 * @param faceVerified set only on server pass + liveness pass (FR-013)
 * @param sameSubject member on file and live face correspond (FR-025)
 * @param verifiedAt freshness anchor in monotonic millis; null until face verification completes
 * @param patient the back office's details for [memberNumber], carried so later steps can show the
 *   operator *who* they are acting for without re-fetching. Deliberately part of the composite
 *   rather than a field of its own: ending the visit drops the whole composite, so the details can
 *   never outlive the patient they describe.
 * @param verificationId the single-use token the face step issued, which enrollment spends. Null
 *   until a face check passes; part of the composite for the same reason [patient] is.
 */
data class VerifiedIdentity(
    val memberNumber: String,
    val memberVerified: Boolean = false,
    val faceVerified: Boolean = false,
    val sameSubject: Boolean = false,
    val verifiedAt: Long? = null,
    val patient: MemberDetails? = null,
    val verificationId: String? = null,
) {
    /**
     * True only when the identity is fully verified and still within the back-office-owned freshness
     * [window] (FR-018, FR-024, FR-026). A null [window] (server did not supply one) is treated as
     * immediately stale — fail-safe re-verification.
     */
    fun isCurrentlyVerified(window: Duration?, nowMillis: Long): Boolean {
        if (!memberVerified || !faceVerified || !sameSubject) return false
        val anchor = verifiedAt ?: return false
        if (window == null) return false
        val elapsed = nowMillis - anchor
        return elapsed >= 0 && elapsed <= window.inWholeMilliseconds
    }
}

/**
 * The ordered, gated steps of the single sequential journey (FR-032). A later step is unreachable
 * until its prerequisite succeeds. Nav guards use [JourneyStep] to decide reachability and to name
 * the outstanding requirement when a step is blocked.
 */
enum class JourneyStep {
    NOT_SIGNED_IN,
    SIGNED_IN,
    MEMBER_SCAN,
    CONSENT,
    FACE_CHECK,
    READY_TO_ENROLL,
    ENROLLMENT,
}

/**
 * Pure gating logic for the sequential journey, expressed over primitive facts so it stays free of
 * device/UI dependencies and is fully unit-testable (FR-032). Consent and lockout are passed as
 * plain booleans so this foundational type does not depend on later-phase models.
 */
object JourneyGate {

    /**
     * The furthest step currently reachable given the facts. The journey collapses to
     * [JourneyStep.NOT_SIGNED_IN] whenever the session is not active (FR-004a).
     */
    fun furthestReachable(
        sessionActive: Boolean,
        memberVerified: Boolean,
        consentGranted: Boolean,
        faceVerified: Boolean,
        currentlyVerified: Boolean,
        lockedOut: Boolean,
    ): JourneyStep = when {
        !sessionActive -> JourneyStep.NOT_SIGNED_IN
        !memberVerified -> JourneyStep.MEMBER_SCAN
        !consentGranted -> JourneyStep.CONSENT
        lockedOut -> JourneyStep.FACE_CHECK
        !faceVerified -> JourneyStep.FACE_CHECK
        currentlyVerified -> JourneyStep.ENROLLMENT
        else -> JourneyStep.MEMBER_SCAN // verified but stale → re-verify (FR-026)
    }

    /** Whether [target] is reachable right now (it is at or below the furthest reachable step). */
    fun canReach(target: JourneyStep, furthest: JourneyStep): Boolean =
        target.ordinal <= furthest.ordinal
}
