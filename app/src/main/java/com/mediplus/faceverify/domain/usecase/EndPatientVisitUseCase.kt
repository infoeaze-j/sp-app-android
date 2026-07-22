package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.session.SessionManager
import javax.inject.Inject

/**
 * Ends the current patient's visit once their service has been recorded, discarding the composite
 * [com.mediplus.faceverify.domain.model.VerifiedIdentity] so the operator returns to the card step
 * with nothing carried over from the patient they just finished (FR-032).
 *
 * This is FR-004a's rule applied at *visit* scope rather than session scope: the operator stays
 * signed in — they have a queue to work through — and the back-office-owned verification window is
 * theirs, not the patient's, so both survive. Only the patient goes.
 */
class EndPatientVisitUseCase @Inject constructor(
    private val sessionManager: SessionManager,
) {
    operator fun invoke() = sessionManager.updateVerifiedIdentity { null }
}
