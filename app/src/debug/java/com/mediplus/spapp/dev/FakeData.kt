package com.mediplus.spapp.dev

import com.mediplus.spapp.domain.model.Currency
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.FaceLockoutState
import com.mediplus.spapp.domain.model.LivenessResult
import com.mediplus.spapp.domain.model.MemberCapabilities
import com.mediplus.spapp.domain.model.MemberDetails
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.MemberVerification
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Provider
import com.mediplus.spapp.domain.model.Service
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState

/** Canned domain payloads for the happy path. Deterministic (no timestamps) for stable tests. */
object FakeData {

    val session: Session = Session(
        token = "fake-token-op-001",
        operator = Operator(operatorId = "op-001", displayName = "Demo Operator", identifier = "demo"),
        expiresAt = null,
        state = SessionState.Active,
        provider = Provider(
            name = "Mercy Hospital",
            id = "prov-001",
            code = "MERCY",
            timezone = "Africa/Johannesburg",
        ),
    )

    /** The id the fake `POST /devices/register` hands back, carried as `X-Device-Id` afterwards. */
    const val deviceId: String = "fake-device-001"

    /** The card number the emulated tap returns. */
    val memberNumber: MemberNumber = MemberNumber.parse("1234567")!!

    /**
     * The bytes the emulated capture returns. Content is irrelevant — [FakeFaceRepository] never
     * inspects it — but it must be non-empty and non-zero so a cleared frame is distinguishable.
     * Hand out `.copyOf()`: TransientFrame.clear() zeroes its array in place.
     */
    val faceFrameBytes: ByteArray = ByteArray(64) { (it + 1).toByte() }

    /** The single-use token the fake face check issues; the fake enrollment spends it. */
    const val verificationId: String = "fake-verification-001"

    val memberDetails: MemberDetails = MemberDetails(
        memberNumber = "1234567",
        fullName = "Jane Doe",
        dateOfBirth = "1985-04-12",
        plan = "Gold",
    )

    val verificationValid: MemberVerification = MemberVerification(
        status = MemberVerification.Status.VALID,
        reason = null,
        referenceOnFile = true,
        member = memberDetails,
        capabilities = MemberCapabilities(canVerifyFace = true, canEnroll = true),
    )

    val verificationInvalid: MemberVerification = MemberVerification(
        status = MemberVerification.Status.INVALID,
        reason = "MEMBERSHIP_EXPIRED",
        referenceOnFile = true,
        member = memberDetails,
        capabilities = MemberCapabilities(canVerifyFace = false, canEnroll = false),
    )

    val services: List<Service> = listOf(
        Service("svc-blood", "BLD", "Blood test", eligibleForPatient = true, alreadyEnrolled = false),
        Service("svc-xray", "XRY", "X-ray", eligibleForPatient = true, alreadyEnrolled = false),
        Service("svc-vaccine", "VAC", "Vaccination", eligibleForPatient = true, alreadyEnrolled = false),
    )

    /**
     * Two currencies with *different* minor-unit exponents, so the amount keypad's scaling is
     * exercised on a bare emulator rather than only ever seeing the two-decimal case.
     */
    val currencies: List<Currency> = listOf(
        Currency("ZAR", "Rand (R)", minorUnitExponent = 2, isDefault = true),
        Currency("JPY", "Japanese Yen (¥)", minorUnitExponent = 0),
    )

    val faceDecisionPass: FaceDecision = FaceDecision(
        decisionPass = true,
        liveness = LivenessResult.PASSED,
        sameSubject = true,
        lockout = FaceLockoutState(lockedOut = false, remainingAttempts = null, cooldownUntilMillis = null),
        verificationId = verificationId,
    )
}
