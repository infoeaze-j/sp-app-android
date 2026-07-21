package com.mediplus.faceverify.dev

import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.FaceLockoutState
import com.mediplus.faceverify.domain.model.LivenessResult
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
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

    /** The card number the emulated tap returns. */
    val memberNumber: MemberNumber = MemberNumber.parse("1234567")!!

    val memberDetails: MemberDetails = MemberDetails(
        memberNumber = "1234567",
        fullName = "Jane Doe",
        dateOfBirth = "1985-04-12",
        membershipStatus = "ACTIVE",
        plan = "Gold",
    )

    val verificationValid: MemberVerification = MemberVerification(
        status = MemberVerification.Status.VALID,
        reason = null,
        memberVerified = true,
        memberResolved = true,
        referenceOnFile = true,
        member = memberDetails,
    )

    val verificationInvalid: MemberVerification = MemberVerification(
        status = MemberVerification.Status.INVALID,
        reason = "MEMBERSHIP_EXPIRED",
        memberVerified = false,
        memberResolved = true,
        referenceOnFile = true,
        member = memberDetails,
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
