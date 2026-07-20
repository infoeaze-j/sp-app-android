package com.mediplus.faceverify.domain.model

/** The patient's consent decision, captured before any biometric capture (FR-028, FR-031). */
enum class ConsentStatus { GRANTED, WITHHELD }

data class BiometricConsent(
    val status: ConsentStatus,
    val recordedAtMillis: Long,
)

/** Server liveness verdict (FR-014). */
enum class LivenessResult { PASSED, FAILED }

/** Outcome of a single face-verification attempt — metadata only, never the image (FR-017). */
sealed interface AttemptOutcome {
    data object Passed : AttemptOutcome
    data class Failed(val reason: String?) : AttemptOutcome
    data object Aborted : AttemptOutcome
}

/**
 * Audit record of one attempt (FR-015, FR-017, FR-030). The captured image is NEVER part of this
 * record — outcome and metadata only.
 */
data class VerificationAttempt(
    val attemptId: String,
    val outcome: AttemptOutcome,
    val reason: String?,
    val liveness: LivenessResult?,
    val timestampMillis: Long,
)

/**
 * Client mirror of the back-office-owned lockout (FR-015). Derived only from server responses and
 * enforced client-side; the server owns it and it persists across sessions.
 */
data class FaceLockoutState(
    val lockedOut: Boolean,
    val remainingAttempts: Int?,
    val cooldownUntilMillis: Long?,
)

/**
 * The authoritative face decision returned by the back office (Decision 2). The app only submits and
 * enforces this; it never computes match or liveness on-device.
 */
data class FaceDecision(
    val decisionPass: Boolean,
    val liveness: LivenessResult,
    val sameSubject: Boolean,
    val reason: String?,
    val lockout: FaceLockoutState,
)
