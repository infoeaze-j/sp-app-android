package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.core.time.TimeProvider
import javax.inject.Inject

/** Which requirement is still outstanding before enrollment is allowed (FR-018, FR-024). */
enum class Outstanding { NONE, DOCUMENT, FACE, STALE }

data class VerificationEvaluation(
    val isCurrentlyVerified: Boolean,
    val outstanding: Outstanding,
)

/**
 * Evaluates the composite [com.mediplus.faceverify.domain.model.VerifiedIdentity] against the
 * back-office-owned freshness window (FR-024, FR-026). An absent window (server didn't supply one)
 * is treated as immediately stale — fail-safe re-verification (FR-026).
 */
class EvaluateVerifiedIdentityUseCase @Inject constructor(
    private val sessionManager: SessionManager,
    private val time: TimeProvider,
) {
    operator fun invoke(): VerificationEvaluation {
        val identity = sessionManager.verifiedIdentity.value
        val window = sessionManager.verificationWindow.value
        val now = time.nowMillis()
        return when {
            identity == null || !identity.memberVerified -> VerificationEvaluation(false, Outstanding.DOCUMENT)
            !identity.faceVerified || !identity.sameSubject -> VerificationEvaluation(false, Outstanding.FACE)
            !identity.isCurrentlyVerified(window, now) -> VerificationEvaluation(false, Outstanding.STALE)
            else -> VerificationEvaluation(true, Outstanding.NONE)
        }
    }
}
