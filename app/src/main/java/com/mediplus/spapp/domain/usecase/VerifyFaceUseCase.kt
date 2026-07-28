package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.core.time.TimeProvider
import com.mediplus.spapp.data.repository.FaceRepository
import com.mediplus.spapp.domain.model.BiometricConsent
import com.mediplus.spapp.domain.model.ConsentStatus
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.FaceLockoutState
import com.mediplus.spapp.domain.model.LivenessResult
import javax.inject.Inject

/** Rich outcome of a face check, carrying the lockout state the UI needs (FR-013–FR-015, FR-025). */
sealed interface FaceCheckResult {
    /** Pass + liveness + same subject — the identity is face-verified. */
    data class Verified(val decision: FaceDecision) : FaceCheckResult

    /** A definitive rejection (no-match, spoof, mismatch, locked-out, consent). */
    data class Rejected(val code: BusinessCode, val lockout: FaceLockoutState?) : FaceCheckResult

    /** A transient/uncertain transport outcome to surface via the shared error mapper. */
    data class Error(val error: AppError) : FaceCheckResult
}

/**
 * Enforces the face-check rules (FR-013–FR-015, FR-025, FR-028): consent-gated, lockout-aware, halts
 * on a same-subject discrepancy, and marks the identity face-verified ONLY on pass + liveness + same
 * subject. The captured [frame] is always cleared (by this use case on an early exit, or by the
 * repository once submitted) so nothing biometric lingers (FR-017).
 */
class VerifyFaceUseCase @Inject constructor(
    private val faceRepository: FaceRepository,
    private val sessionManager: SessionManager,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(
        consent: BiometricConsent,
        lockout: FaceLockoutState?,
        frame: TransientFrame,
    ): FaceCheckResult {
        if (consent.status != ConsentStatus.GRANTED) {
            frame.clear()
            return FaceCheckResult.Rejected(BusinessCode.CONSENT_WITHHELD, null)
        }
        if (lockout?.lockedOut == true) {
            frame.clear()
            return FaceCheckResult.Rejected(BusinessCode.FACE_LOCKED_OUT, lockout)
        }
        val memberNumber = sessionManager.verifiedIdentity.value?.memberNumber
        if (memberNumber == null) {
            frame.clear()
            return FaceCheckResult.Rejected(BusinessCode.NOT_CURRENTLY_VERIFIED, null)
        }
        return when (val result = faceRepository.verify(memberNumber, frame)) {
            is AppResult.Success -> interpret(result.data)
            is AppResult.BusinessRejection -> FaceCheckResult.Rejected(result.error.code, null)
            is AppResult.TransientFailure -> FaceCheckResult.Error(result.error)
            AppResult.Timeout -> FaceCheckResult.Error(AppError.Timeout)
        }
    }

    private fun interpret(decision: FaceDecision): FaceCheckResult {
        // Different person → halt and record a discrepancy (FR-025) before any match consideration.
        if (!decision.sameSubject) return FaceCheckResult.Rejected(BusinessCode.SUBJECT_MISMATCH, decision.lockout)
        if (decision.liveness == LivenessResult.FAILED) {
            return FaceCheckResult.Rejected(BusinessCode.FACE_SPOOF, decision.lockout)
        }
        if (!decision.decisionPass) return FaceCheckResult.Rejected(BusinessCode.FACE_NO_MATCH, decision.lockout)
        if (decision.lockout.lockedOut) return FaceCheckResult.Rejected(BusinessCode.FACE_LOCKED_OUT, decision.lockout)

        // The issued verification id rides on the composite: enrollment spends it, and ending the
        // visit drops it with everything else, so it can never be reused for a different patient.
        sessionManager.updateVerifiedIdentity {
            it?.copy(
                faceVerified = true,
                sameSubject = true,
                verifiedAt = time.nowMillis(),
                verificationId = decision.verificationId,
            )
        }
        return FaceCheckResult.Verified(decision)
    }
}
