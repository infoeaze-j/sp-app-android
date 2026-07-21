package com.mediplus.faceverify.dev

import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.FaceLockoutState
import com.mediplus.faceverify.domain.model.LivenessResult
import com.mediplus.faceverify.domain.model.Operator
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState

/** Canned domain payloads for the happy path. Deterministic (no timestamps) for stable tests. */
object FakeData {

    val session: Session = Session(
        token = "fake-token-op-001",
        operator = Operator(operatorId = "op-001", displayName = "Demo Operator"),
        expiresAt = null,
        state = SessionState.Active,
    )

    val validationValid: DocumentValidation = DocumentValidation(
        authenticity = DocumentValidation.Authenticity.VALID,
        reason = null,
        documentVerified = true,
        referenceOnFile = true,
        patientResolved = true,
    )

    val validationInvalid: DocumentValidation = DocumentValidation(
        authenticity = DocumentValidation.Authenticity.INVALID,
        reason = "Document expired",
        documentVerified = false,
        referenceOnFile = true,
        patientResolved = true,
    )

    val services: List<Service> = listOf(
        Service("svc-blood", "Blood test", eligibleForPatient = true, alreadySelected = false),
        Service("svc-xray", "X-ray", eligibleForPatient = true, alreadySelected = false),
        Service("svc-vaccine", "Vaccination", eligibleForPatient = true, alreadySelected = false),
    )

    val faceDecisionPass: FaceDecision = FaceDecision(
        decisionPass = true,
        liveness = LivenessResult.PASSED,
        sameSubject = true,
        reason = null,
        lockout = FaceLockoutState(lockedOut = false, remainingAttempts = null, cooldownUntilMillis = null),
    )
}
