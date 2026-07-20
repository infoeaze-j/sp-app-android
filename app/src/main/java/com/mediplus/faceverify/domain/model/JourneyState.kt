package com.mediplus.faceverify.domain.model

import kotlin.time.Duration

/**
 * The composite precondition for enrollment (FR-024, FR-025, FR-026). Enrollment is permitted only
 * when every part is satisfied *and* the verification is still fresh.
 *
 * @param documentNumber ties the composite to exactly one patient (FR-011a)
 * @param documentVerified set only on server `Valid` + locally not-expired (FR-008)
 * @param faceVerified set only on server pass + liveness pass (FR-013)
 * @param sameSubject document subject and live face correspond (FR-025)
 * @param verifiedAt freshness anchor in monotonic millis; null until face verification completes
 */
data class VerifiedIdentity(
    val documentNumber: String,
    val documentVerified: Boolean = false,
    val faceVerified: Boolean = false,
    val sameSubject: Boolean = false,
    val verifiedAt: Long? = null,
) {
    /**
     * True only when the identity is fully verified and still within the back-office-owned freshness
     * [window] (FR-018, FR-024, FR-026). A null [window] (server did not supply one) is treated as
     * immediately stale — fail-safe re-verification.
     */
    fun isCurrentlyVerified(window: Duration?, nowMillis: Long): Boolean {
        if (!documentVerified || !faceVerified || !sameSubject) return false
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
    DOCUMENT_SCAN,
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
        documentVerified: Boolean,
        consentGranted: Boolean,
        faceVerified: Boolean,
        currentlyVerified: Boolean,
        lockedOut: Boolean,
    ): JourneyStep = when {
        !sessionActive -> JourneyStep.NOT_SIGNED_IN
        !documentVerified -> JourneyStep.DOCUMENT_SCAN
        !consentGranted -> JourneyStep.CONSENT
        lockedOut -> JourneyStep.FACE_CHECK
        !faceVerified -> JourneyStep.FACE_CHECK
        currentlyVerified -> JourneyStep.ENROLLMENT
        else -> JourneyStep.DOCUMENT_SCAN // verified but stale → re-verify (FR-026)
    }

    /** Whether [target] is reachable right now (it is at or below the furthest reachable step). */
    fun canReach(target: JourneyStep, furthest: JourneyStep): Boolean =
        target.ordinal <= furthest.ordinal
}
