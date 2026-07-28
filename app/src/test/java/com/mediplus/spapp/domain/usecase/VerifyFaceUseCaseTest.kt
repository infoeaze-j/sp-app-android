package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.core.time.TimeProvider
import com.mediplus.spapp.data.repository.FaceRepository
import com.mediplus.spapp.domain.model.BiometricConsent
import com.mediplus.spapp.domain.model.ConsentStatus
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.FaceLockoutState
import com.mediplus.spapp.domain.model.LivenessResult
import com.mediplus.spapp.domain.model.VerifiedIdentity
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T037 — VerifyFaceUseCase: consent-gated, lockout-aware, halts on a same-subject discrepancy, and
 * marks face-verified only on pass + liveness + same subject (FR-013, FR-015, FR-025, FR-028).
 */
class VerifyFaceUseCaseTest {

    private val faceRepository = mockk<FaceRepository>()
    private lateinit var sessionManager: InMemorySessionManager
    private val now = 1_000L
    private lateinit var useCase: VerifyFaceUseCase

    private val grantedConsent = BiometricConsent(ConsentStatus.GRANTED, recordedAtMillis = 0)
    private val notLocked = FaceLockoutState(lockedOut = false, remainingAttempts = 3, cooldownUntilMillis = null)

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        sessionManager.updateVerifiedIdentity { VerifiedIdentity("P1", memberVerified = true) }
        useCase = VerifyFaceUseCase(faceRepository, sessionManager, TimeProvider { now })
    }

    private fun decision(
        pass: Boolean = true,
        liveness: LivenessResult = LivenessResult.PASSED,
        sameSubject: Boolean = true,
        lockout: FaceLockoutState = notLocked,
        verificationId: String? = "ver-1",
    ) = FaceDecision(pass, liveness, sameSubject, lockout, verificationId)

    @Test
    fun `withheld consent halts without capture`() = runTest {
        val frame = TransientFrame(byteArrayOf(1))

        val result = useCase(BiometricConsent(ConsentStatus.WITHHELD, 0), notLocked, frame)

        assertEquals(BusinessCode.CONSENT_WITHHELD, (result as FaceCheckResult.Rejected).code)
        assertTrue(frame.isCleared)
        coVerify { faceRepository wasNot Called }
    }

    @Test
    fun `active lockout blocks the attempt`() = runTest {
        val locked = FaceLockoutState(lockedOut = true, remainingAttempts = 0, cooldownUntilMillis = 5_000)

        val result = useCase(grantedConsent, locked, TransientFrame(byteArrayOf(1)))

        val rejected = result as FaceCheckResult.Rejected
        assertEquals(BusinessCode.FACE_LOCKED_OUT, rejected.code)
        coVerify { faceRepository wasNot Called }
    }

    @Test
    fun `pass with liveness and same subject marks face-verified`() = runTest {
        coEvery { faceRepository.verify(any(), any()) } returns AppResult.Success(decision())

        val result = useCase(grantedConsent, notLocked, TransientFrame(byteArrayOf(1)))

        assertTrue(result is FaceCheckResult.Verified)
        val identity = sessionManager.verifiedIdentity.value
        assertTrue(identity?.faceVerified == true)
        assertTrue(identity?.sameSubject == true)
        assertEquals(now, identity?.verifiedAt)
    }

    @Test
    fun `different subject halts as a discrepancy`() = runTest {
        coEvery { faceRepository.verify(any(), any()) } returns AppResult.Success(decision(sameSubject = false))

        val result = useCase(grantedConsent, notLocked, TransientFrame(byteArrayOf(1)))

        assertEquals(BusinessCode.SUBJECT_MISMATCH, (result as FaceCheckResult.Rejected).code)
    }

    @Test
    fun `spoof is rejected regardless of match`() = runTest {
        coEvery { faceRepository.verify(any(), any()) } returns
            AppResult.Success(decision(pass = true, liveness = LivenessResult.FAILED))

        val result = useCase(grantedConsent, notLocked, TransientFrame(byteArrayOf(1)))

        assertEquals(BusinessCode.FACE_SPOOF, (result as FaceCheckResult.Rejected).code)
    }

    @Test
    fun `no match is recorded as a failure`() = runTest {
        coEvery { faceRepository.verify(any(), any()) } returns AppResult.Success(decision(pass = false))

        val result = useCase(grantedConsent, notLocked, TransientFrame(byteArrayOf(1)))

        assertEquals(BusinessCode.FACE_NO_MATCH, (result as FaceCheckResult.Rejected).code)
    }

    @Test
    fun `transient failure is surfaced as an error`() = runTest {
        coEvery { faceRepository.verify(any(), any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))

        val result = useCase(grantedConsent, notLocked, TransientFrame(byteArrayOf(1)))

        assertTrue(result is FaceCheckResult.Error)
    }
}
