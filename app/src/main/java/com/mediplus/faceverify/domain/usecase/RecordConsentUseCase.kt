package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.time.TimeProvider
import com.mediplus.faceverify.domain.model.BiometricConsent
import com.mediplus.faceverify.domain.model.ConsentStatus
import javax.inject.Inject

/** Result of recording the patient's consent (FR-028). */
sealed interface ConsentDecision {
    val consent: BiometricConsent

    /** Consent granted — the face check may proceed. */
    data class Proceed(override val consent: BiometricConsent) : ConsentDecision

    /** Consent withheld — record it and halt the journey cleanly; no capture, no enrollment. */
    data class Halt(override val consent: BiometricConsent) : ConsentDecision
}

/**
 * Records the patient's consent decision before any capture (FR-028, FR-031). On withheld, the
 * journey halts cleanly with the decision recorded for audit — there is no non-biometric alternative.
 */
class RecordConsentUseCase @Inject constructor(
    private val time: TimeProvider,
) {
    operator fun invoke(status: ConsentStatus): ConsentDecision {
        val consent = BiometricConsent(status, time.nowMillis())
        return if (status == ConsentStatus.GRANTED) {
            ConsentDecision.Proceed(consent)
        } else {
            ConsentDecision.Halt(consent)
        }
    }
}
