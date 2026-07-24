package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.core.time.TimeProvider
import com.mediplus.faceverify.domain.model.MemberDetails
import javax.inject.Inject

/** Which requirement is still outstanding before enrollment is allowed (FR-018, FR-024). */
enum class Outstanding { NONE, DOCUMENT, FACE, STALE }

/**
 * @param patient whoever the composite currently describes, so a caller that already asks "may I
 *   proceed?" doesn't need a second dependency to ask "for whom?". Null when nothing is verified.
 */
data class VerificationEvaluation(
    val isCurrentlyVerified: Boolean,
    val outstanding: Outstanding,
    val patient: MemberDetails? = null,
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
        val outstanding = when {
            identity == null || !identity.memberVerified -> Outstanding.DOCUMENT
            !identity.faceVerified || !identity.sameSubject -> Outstanding.FACE
            !identity.isCurrentlyVerified(window, now) -> Outstanding.STALE
            else -> Outstanding.NONE
        }
        return VerificationEvaluation(
            // Nothing outstanding is the definition of currently verified — deriving it here keeps
            // the two from ever disagreeing.
            isCurrentlyVerified = outstanding == Outstanding.NONE,
            outstanding = outstanding,
            patient = identity?.patient,
        )
    }
}
